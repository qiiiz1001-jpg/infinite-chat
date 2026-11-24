# 03：WebSocket 接入与一条消息的分发

[返回学习导航](00-学习导航.md) · [原文与代码进度](进度-原文与代码对照.md)

## 本篇目标

对应原文第 3 章“初步实现 RealtimeService”和第 4 章“实现消费者 OfflineDataService”，按这个顺序理解连接、鉴权、心跳、消息分流和消费入口。**以下流程与当前源码一致**；后续章节已经补入的会话授权、落库等能力，在这里说明接入位置，第 05 篇再展开具体实现。

学完能区分“进入 Kafka”“写回 Channel”“数据库提交”这三个时刻。普通 DTO 不逐个读，按下面六个入口追完整链路。

## 1. 场景：A 在线给 B 发一句“你好”

用演示账号 A=1001、B=2002、单聊会话 99001 串流程。这些记录由演示 SQL 提供，只有执行初始化后才实际存在。

```text
A、B 登录 UserService，取得 Access Token 和 nettyUri
    ↓
连接 nettyUri，握手携带 Authorization
    ↓ JWT 验签与有效期 + Redis 当前凭证
握手完成，绑定当前节点的 userId ↔ Channel
    ↓ A 发送文本帧；先核对连接凭证是否仍有效
    ↓ 参数校验 → 覆盖 senderId → RPC 校验会话权限
    ↓ 服务端生成 messageId、createdTime
    ├─ store-topic → OfflineDataService
    │                 → MessageServiceImpl → MySQL 幂等写入
    │                 → 事务提交后更新 Redis 最近消息索引
    └─ message-topic → 各实时节点的独立消费组
                      → 查本机 Channel → A 回显、B 在线推送
```

两条消费链独立推进，推送不等待数据库落库。B 不在线时跳过在线写出，之后通过离线补拉或历史查询获取已存储消息。

和黑马点评的短请求相比，主要变化是服务端持有长连接，可以主动发送数据；不能只用“Controller 返回一次响应”理解聊天推送。

## 2. 按这个顺序打开六个入口

### 第一步：Netty 如何接管连接

打开 [NettyService.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/config/NettyService.java)，看 `start → initChannel`，最后看 `stop`。

Spring 创建组件后，`@PostConstruct` 启动 Netty，默认监听独立端口 9101。RealTimeService 的 Spring HTTP 端口是 8102，WebSocket 直接进入 Netty Pipeline，不经过 MVC Controller。

```text
IdleStateHandler：检测读空闲
  → HttpServerCodec：HTTP 编解码
  → HttpObjectAggregator：聚合握手请求
  → WebSocketAuthHeader：验证身份
  → WebSocketServerProtocolHandler：协议升级
  → WebSocketFrameAggregator：聚合分片帧
  → WebSocketHandler：心跳与聊天业务
```

Boss 接受连接，Worker 处理网络 I/O。鉴权和业务 Handler 绑定独立业务执行器，避免 Redis、Feign、Kafka 的阻塞操作占住 I/O 线程；同一 Channel 在同一处理器内按序处理。

`start` 等待端口绑定成功，不等待服务器关闭。`stop` 关闭监听、客户端连接与线程组，避免退出后端口或线程残留。

### 第二步：把“连上了”变成“知道是谁连上了”

打开 [WebSocketAuthHeader.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketAuthHeader.java)，看 `channelRead`：

```text
取 Authorization（支持裸 Token 或 Bearer Token）
    → JwtUtil.parse 验签、检查过期
    → 从 subject 取 userId
    → 核对 Redis access:token:{userId} 的当前值
    → 把 userId、Token 存进 Channel 属性
```

JWT 合法但 Redis 不匹配仍拒绝连接。认证通过后移除鉴权 Handler，继续协议升级；**升级成功才把连接发布到在线索引**。拒绝握手时还要释放请求的引用计数，并关闭连接。

共用契约见 [JwtUtil](../../Common/src/main/java/cn/qingliao/common/utils/JwtUtil.java) 和 [CommonConstant](../../Common/src/main/java/cn/qingliao/common/constant/CommonConstant.java)。Common 是共享 JAR，不是独立服务。

当前还补了连接存续期间的校验：看 [ConnectionStateService.isCurrent](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/ConnectionStateService.java)。每个入站文本帧（包括 ping）都会核对 Redis 当前凭证。退出或凭证被替换后，旧连接在**下一次文本帧**到达时关闭；这不是退出瞬间主动踢连接，无入站数据还会由空闲超时关闭。

### 第三步：以后怎么找到 B 的连接

打开 [ChannelManager.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/ChannelManager.java)，看 `bind、getChannelByUserId、unbind`；再回到 [WebSocketHandler.userEventTriggered](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java)。

握手完成事件触发 `bind`，建立当前节点 userId 到 Channel 的映射。当前节点同一用户的新连接会替换并关闭旧连接；旧关闭回调使用 `remove(userId, channel)`，只删除自己，避免误删刚建立的新连接。清理当前映射成功后，回调记录离线时间。

这份 Map 只在当前 JVM 中，不能直接查到其他节点的 Channel。**当前推送已经采用每节点独立消费组**：各节点收到消息后筛选本机连接。第 04 篇的 Nacos、一致性哈希解决客户端选哪个节点；它和“Kafka 消息怎样到达持有连接的节点”是两个问题。

### 第四步：一条文本消息如何进入 Kafka

仍看 [WebSocketHandler.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java)，按 `channelRead0 → validate → send` 读。

客户端文本帧示例：

```json
{
  "sessionId": "99001",
  "receiverId": "2002",
  "type": 0,
  "sessionType": 0,
  "content": "你好",
  "clientMessageId": "client-1"
}
```

本篇用单聊文本说明流程；当前 Handler 已支持 sessionType=0 单聊、1 群聊、2 机器人，以及 type=0 文本、1 图片、2 表情、4 文件。群聊不传 receiverId；type=3 红包必须走红包 HTTP 业务，不能从普通 WebSocket 伪造发送。上传与 AI 的完整流程见第 05 篇，红包见第 07 篇。

消息处理依次做四件事：

1. 校验参数并统一正文结构；既支持示例中的顶层 content，也读取 body.content。
2. 从 Channel 取可信 userId，覆盖 senderId，不能用客户端 senderId 判断身份。
3. 通过 Feign 调用 [SessionAccessController.canSend](../../UserService/src/main/java/cn/qingliao/userservice/controller/SessionAccessController.java)：核对用户、会话状态和成员关系；单聊还核对双向关系是否存在、发送方未删除关系且接收方未拉黑发送方；发送方主动拉黑对方不阻止自己发送。认证成功不等于有权访问任意会话。
4. 生成 messageId、createdTime，把同一消息以 sessionId 为 key 投递到 store-topic 与 message-topic。机器人消息另发 ai-request-topic，细节放到第 05 篇。

ID 入口是 [SnowflakeDynamicUtil](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/utils/SnowflakeDynamicUtil.java)，通过构造器读取节点号。核对时发现实时服务配置曾默认传入 -1，而这个生成器不处理负数回退，已将实时服务默认值改为 2。多实例必须分配不同节点号；雪花 ID 不能替代业务重试去重。

同会话 key 让消息在同一 Topic 落入同一分区，不保证两个 Topic 之间的消费进度，也不保证不同连接发送时的全局业务顺序。两次 Kafka 发送不属于同一个事务，失败提示不表示另一路没成功。

`clientMessageId` 当前用于关联发送与回显，尚未做客户端请求幂等。同一个客户端请求重发可能生成新 messageId，不能和数据库对同一 messageId 的去重混淆。

`ping` 在核对连接凭证后直接返回 `pong`，不进入 Kafka。默认 60 秒没有入站数据则关闭连接。心跳只说明连接有往来，不证明聊天消息送达。

### 第五步：Kafka 消息怎么变回客户端可见的消息

打开 [ConsumerMessageService.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/consumer/ConsumerMessageService.java)，按 `consume → createMessageResponse → pushMessageToUser` 读。

监听 message-topic 的组名为 `infinite-chat-push-${chat.node-id:node-1}`。多个节点必须配置不同 CHAT_NODE_ID；若用了同一组，它们会分摊分区，收到消息的节点可能没有目标连接。

单聊给发送者回显、给接收者推送；群聊通过用户服务取得成员列表，依次尝试推送。当前节点查到活跃 Channel 后，用 `writeAndFlush(TextWebSocketFrame)` 写出；离线或在别的节点的用户，本节点跳过。

客户端收到的双方回显使用同一服务端 messageId。输出经 [WireJson](../../Common/src/main/java/cn/qingliao/common/utils/WireJson.java) 将 Long 序列化为字符串，避免 JavaScript Number 丢精度；createdTime 为毫秒时间戳字符串。HTTP 同样配置 Long 字符串输出。

`writeAndFlush` 成功只代表本机写操作完成，不等于对方已读或数据库提交。当前推送直接复制消息字段，未在这里补查发送者昵称、头像。

### 第六步：离线服务收到消息后做了什么

打开 [ConsumerOfflineService.java](../../OfflineDataService/src/main/java/cn/qingliao/offlinedataservice/consumer/ConsumerOfflineService.java)，看 `consume`。**当前已经调用 messageService.saveMessageToMySQL，不是只打印消费日志。**

继续跟到 [MessageServiceImpl.saveMessageToMySQL](../../OfflineDataService/src/main/java/cn/qingliao/offlinedataservice/service/impl/MessageServiceImpl.java) → [MessageMapper.insertIdempotently](../../OfflineDataService/src/main/java/cn/qingliao/offlinedataservice/mapper/MessageMapper.java)：

```text
解析 MessageRequest → 组装数据库实体
    → INSERT ... ON DUPLICATE KEY UPDATE message_id=message_id
    → 数据库事务提交
    → afterCommit 更新 Redis 会话消息 ZSET
```

同一 messageId 重复消费不会重复插入或覆盖原文；数据库失败不能被当成消费成功。缓存更新失败会记录日志，数据库仍是事实来源。存储消费者共用 `infinite-chat-store-group` 分摊工作，它和推送的每节点独立组用途不同。

当前已有历史、离线补拉接口，分别在 `getHistoryMessages、getOfflineMessages`。这里只确认接入位置；复合游标、缓存缺失回源、Canal 与查询权限，继续看[第 05 篇](05-消息存储查询与AI链路.md)。

## 3. 面试时重点讲清的边界

| 追问 | 结合当前代码回答 |
|---|---|
| 为什么引入 Kafka？ | 解耦存储与推送，让它们独立消费和扩展；代价是异步失败、积压、重投与排障复杂度。发送调用也可能等待元数据。 |
| 推送和落库能保证同时成功吗？ | 不能。双 Topic 非原子发布，两路消费也独立；普通消息缺少完整投递补偿与端到端持久化 ACK。 |
| 有没有做幂等？ | MySQL 已按服务端 messageId 做存储幂等；尚未按 clientMessageId 做客户端重试幂等。生产者协议幂等也不能替代这两种业务判断。 |
| 退出后旧连接还能用吗？ | 下一次入站文本帧会校验 Redis 并关闭失效连接；无数据时由空闲超时关闭，不是退出瞬间主动踢连接。 |
| 身份可信就能发到任意会话吗？ | 不能。senderId 取自连接后，还要通过 canSend 校验会话成员、状态与单聊好友关系。 |
| 扩成两个实时节点怎么推送？ | 每节点独立消费组，各自过滤本地连接；不是所有节点共用一组。代价是节点增加会放大消息消费量。 |

## 4. 与原文的必要修正及验证

原文中的类名与推送 Topic 已统一；补齐正文、引用字段、服务端可信身份、构造器注入雪花配置、旧连接清理比较和拒绝握手的引用计数释放。后续章节落地后，本篇已同步会话授权、存储和在线状态检查，避免讲义继续描述旧的中间阶段。

配置入口：[实时服务配置](../../RealTimeService/src/main/resources/application.yml)、[离线服务配置](../../OfflineDataService/src/main/resources/application.yml)、[Topic 初始化](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/config/KafkaTopicConfig.java)。当前存储、推送、AI Topic 声明为 3 分区、1 副本；单副本环境的 acks=all 不提供多副本容灾。消费采用 record 确认模式，监听方法正常完成不等于客户端已读。

测试入口：[握手鉴权](../../RealTimeService/src/test/java/cn/qingliao/realtimeservice/websocket/WebSocketAuthTest.java)、[消息与连接边界](../../RealTimeService/src/test/java/cn/qingliao/realtimeservice/websocket/MessageFlowTest.java)、[真实本机 WebSocket](../../RealTimeService/src/test/java/cn/qingliao/realtimeservice/websocket/NettySocketTest.java)、[真实测试数据库存储与查询](../../OfflineDataService/src/test/java/cn/qingliao/offlinedataservice/MessagePersistenceTest.java)。

2026-10-24 的验证记录为全工程 46 项自动化测试通过。2026-10-26 更新本篇时，重新执行 JWT 2 项与实时链路 10 项测试，共 12 项通过，讲义链接与 YAML 默认配置检查通过，具体范围见[验证记录](../verification.md)。真实本机 Netty 与 H2 数据库测试不等于真实 Redis、Kafka、MySQL 已联调；本次未重跑全部 46 项。

后续可在 Maven 工程根目录使用 JDK 17 执行相关检查：

```powershell
mvn -pl RealTimeService,OfflineDataService -am test '-Dchat.build-dir=target-maven' '-Dtest=JwtContractTest,WebSocketAuthTest,MessageFlowTest,NettySocketTest,MessagePersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

真实环境准备与演示步骤统一见[部署说明](../../deploy/README.md)：初始化数据库与升级脚本，启动 Redis、Kafka 及业务服务，再运行握手、消息、存储与补拉检查。当前消费者落库后 message 表应有记录，不能再按旧说明预期“只打印日志”。普通浏览器 WebSocket 无法直接设置 Authorization 握手头，当前演示需使用支持自定义头的客户端。

下一篇：[04：网关鉴权与实时节点定位](04-网关鉴权与实时节点定位.md)。

## 本篇自查

1. A 发出的 JSON 如何到达 B，并写入数据库？说出关键入口。
2. senderId 可信后，为什么还要调用 canSend？
3. B 重连后，旧连接的清理如何避免误删新连接？
4. 收到回显能否证明落库？当前实现了哪种幂等？
5. 多个实时节点与多个存储实例，分别怎样配置消费组？
6. 退出登录、心跳和 record 确认分别保证到哪里？

## 自查解答

**1. 先接入，再分成两条消费链。** NettyService 建 Pipeline；WebSocketAuthHeader 认证；ChannelManager 保存本机映射；WebSocketHandler 校验连接与会话、生成 ID 后投递 Topic；ConsumerMessageService 找 Channel 推送；ConsumerOfflineService 调用 MessageServiceImpl，再由 MessageMapper 幂等写库，提交后更新缓存。

**2. 认证只解决“你是谁”。** senderId 来自可信连接，仍不能证明该用户属于指定会话、接收者属于同一单聊或接收者未拉黑发送者。canSend 用数据库业务关系授权，不能由客户端参数替代。

**3. 删除时比较旧 Channel。** 新连接先覆盖 Map 后，旧回调若只按 userId 删除会误删新映射；remove(userId, oldChannel) 只在映射仍指向旧连接时删除，并且只有成功移除当前连接才记录本次离线时间。

**4. 回显不能证明落库。** 推送与存储独立消费。当前 MySQL 按服务器 messageId 去重，保护同条 Kafka 消息重投；客户端重发会产生新 ID，clientMessageId 幂等尚未实现。双 Topic 部分成功仍是失败窗口。

**5. 推送按节点广播，存储在组内分工。** 实时节点使用不同 CHAT_NODE_ID 组成独立推送组，再过滤本机连接；存储实例共用存储组分摊分区，用数据库幂等承受重复消费。节点发现与哈希定位不能替代消费组设计。

**6. 各自只约束一段链路。** 退出使 Redis 凭证失效，旧连接下一次文本帧或空闲超时才关闭；ping/pong 不证明消息送达；record 确认不证明客户端已读。存储监听中的事务返回意味着数据库事务已完成，但推送监听的确认不提供数据库提交证明。
