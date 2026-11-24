# 补充：Kafka 消费与在线消息推送

[先看 Kafka 结构图与概念总览](补充-Kafka结构图与概念总览.md) · [返回学习导航](00-学习导航.md)

## 本篇目标

用黑马点评中 Redis Stream 消息队列的知识，读懂 ConsumerMessageService：谁调用 consume、payload 从哪里来、消费组为什么带节点 ID，以及消息如何从 Kafka 回到 A、B 的客户端。

本篇是已有业务链的源码答疑，不推进原文章节、不修改业务实现。核对范围包含当前消费者、消息生产者、Topic 声明、Kafka 配置、响应序列化与连接索引；只做静态核对，未启动 Kafka 或联调。

## 具体业务场景

A=1001、B=2002，在单聊会话 99 中发送“你好”。两人已完成 WebSocket 握手，服务器持有各自 Channel。

```text
A 客户端
    → WebSocketHandler.channelRead0
    → kafka.send("message-topic", "99", 消息 JSON)
    → Kafka 保存到某个分区
    → Spring Kafka 消费容器拉取消息
    → ConsumerMessageService.consume(payload)
    → 查询 A、B 在本节点的 Channel
    → writeAndFlush
    → 客户端接收并更新界面
```

存储使用另一条 store-topic 链路，不在这个消费者中执行。A、B 是 WebSocket 客户端；这里的 Kafka 消费者是实时服务里的程序。

## 分步调用流程与文件入口

### 第一步：先与 Redis Stream 建立对应关系

如果学过的是黑马点评的 Stream 消费组，可以先按下表类比；这些是帮助阅读的近似对应，不代表协议和确认机制完全相同。

| Redis Stream 中熟悉的东西 | Kafka 中的对应概念 | 本项目例子 |
|---|---|---|
| XADD 追加消息 | 生产者发送记录 | WebSocketHandler 中 kafka.send |
| Stream 的名字，例如 stream.orders | Topic，消息的逻辑分类 | message-topic |
| 一条 Stream 记录 | 一条 Kafka record | 补齐服务端字段后的聊天 JSON |
| XREADGROUP 读取 | KafkaConsumer.poll；这里由 Spring 消费容器调用 | @KafkaListener 对应的后台消费任务 |
| 消费组名称 | group.id | infinite-chat-push-node-1 |
| 已处理确认与消费进度 | 提交分区消费位点 | 当前使用 Spring RECORD 模式 |

Kafka 的 Topic 还包含 Partition（分区）。每条记录被追加到一个分区，并获得该分区内的位置 offset。Kafka 的 offset 与业务 messageId 不同；不同分区可出现相同 offset。参考 [Kafka 基本概念](https://kafka.apache.org/41/getting-started/introduction/)。

Kafka 消费不会因读完就删除记录，而是维护消费进度，记录按保留策略管理。Redis Stream 的普通 XACK 也只是确认、移出 Pending 引用，并不等于 XDEL；不要把它们都当成 List 的弹出删除。参考 [Redis Stream 消费与确认](https://redis.io/docs/latest/develop/data-types/streams/)。

### 第二步：消息从哪里写进 Kafka

打开 [WebSocketHandler.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java)，搜索 KAFKA_MESSAGE_TOPIC_PUSH。

```java
String payload = JSONUtil.toJsonStr(request);
String key = request.getSessionId().toString();
CompletableFuture<SendResult<String, String>> push =
        send(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH, key, payload);
```

send 最终调用 kafka.send(topic, key, payload)。这里 key 为会话 ID 字符串，value 是消息 JSON。当前普通 key 分区策略和固定分区数下，相同 key 会进入同一分区；这不等于两个 Topic 之间有统一顺序，也不意味着不同连接发送的消息天然按用户感知时间排序。

[KafkaTopicConfig.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/config/KafkaTopicConfig.java) 声明推送 Topic 为 3 分区、1 副本，属于当前学习环境的期望配置，未连接 Broker 验证实际现有 Topic。分区有利于存放和并行处理数据；配置 3 分区不等于代码自动启动 3 个业务处理线程。

### 第三步：谁调用 consume

打开 [ConsumerMessageService.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/consumer/ConsumerMessageService.java)，从类上的 @Service 和 consume 上的注解读起：

```java
@KafkaListener(
        topics = CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH,
        groupId = "infinite-chat-push-${chat.node-id:node-1}")
public void consume(String payload) {
    // 消息解析与推送
}
```

@Service 让 Spring 管理这个 Bean；@KafkaListener 将方法注册为消费监听入口。自动配置下，Spring Kafka 的监听容器在后台使用消费者拉取消息，再由监听适配器调用这个方法。不是客户端访问这个方法，也不是 Kafka Broker 直接调用 Java 方法。

这里 value 使用 StringDeserializer 解码，payload 因此是生产者发送的 JSON 字符串。参数名 payload 不是关键字，也可以叫 message；监听器配置和参数类型决定接收方式。参考 [Spring Kafka 3.3 的监听注解](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/listener-annotation.html)。

与手写 Redis 队列消费循环相比，原来自己写的“不断读取，再调用处理函数”，在这里大部分由监听容器负责。默认这不是 Netty Worker 的收消息回调，消费容器有自己的执行线程；写 Channel 时再由 Netty 安排网络写操作。

### 第四步：解析消息并生成响应结构

```java
MessageRequest request = JSONUtil.toBean(payload, MessageRequest.class);
MessageResponse response = createMessageResponse(request);
```

例如 payload 的内容含 sessionId=99、senderId=1001、receiverId=2002、content="你好"、服务端 messageId 和 createdTime。它是在生产阶段已经补齐的消息，消费者不重新分配消息 ID。

createMessageResponse 复制字段，并把 createdTime 转为毫秒时间戳字符串。其作用是整理客户端需要的响应结构，不查询数据库，也不在这里补查昵称头像。BeanUtil 的普通复制不必逐行展开。

### 第五步：决定推送给哪些用户

```java
if (Integer.valueOf(1).equals(request.getSessionType())) {
    for (Long userId : users.getUserIdBySessionId(request.getSessionId()))
        pushMessageToUser(response, userId);
} else {
    pushMessageToUser(response, request.getSenderId());
    if (request.getReceiverId() != null
            && !request.getReceiverId().equals(request.getSenderId()))
        pushMessageToUser(response, request.getReceiverId());
}
```

群聊 sessionType=1 时，通过 [UserServiceClient.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/client/UserServiceClient.java) 的 Feign 接口查询会话成员，再逐个推送。

单聊等其他分支先给发送者回显，再给不相同的接收者推送。A 收到回显后，可通过 clientMessageId 关联本地发送中的消息，补齐服务端字段；B 则作为收到的消息显示。消费者负责发数据，界面由客户端绘制。客户端需要关联更新，避免把 A 的本地消息和回显显示成两条。

### 第六步：用用户 ID 查 Channel，执行在线推送

```java
Channel channel = ChannelManager.getChannelByUserId(userId.toString());
if (channel != null && channel.isActive()) {
    channel.writeAndFlush(
            new TextWebSocketFrame(WireJson.write(response)));
}
```

上面省略了源码中的失败监听器。实际实现还会在写操作失败时记录 messageId 和异常。

[ChannelManager.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/ChannelManager.java) 查询的是当前进程的在线 Map。找到活动连接就发送；本机查不到则跳过，不在本方法等待用户上线。离线消息由存储、查询与补拉链路处理。

[WireJson.java](../../Common/src/main/java/cn/qingliao/common/utils/WireJson.java) 显式把 Long 序列化为字符串，保护雪花 ID 等大整数在 JavaScript 中的精度。Netty 写出不经过 Spring MVC，不能只依赖 MVC 的 JSON 配置。

### 第七步：为什么每个实时节点使用不同消费组

消费组是 Kafka 的消费协作单位。普通订阅模式下，同组消费者分配不同分区，同一分区由组内一个消费者负责；不同组维护各自的消费进度。参考 [KafkaConsumer 的消费组说明](https://kafka.apache.org/39/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)。

当前 groupId 用节点配置生成：

```text
节点 1：chat.node-id=node-1 → infinite-chat-push-node-1
节点 2：chat.node-id=node-2 → infinite-chat-push-node-2
```

假设 A 连接节点 1，B 连接节点 2：

```text
message-topic 的同一条记录
    ├─ 组 node-1 消费 → 本机只有 A 的 Channel → 给 A 回显
    └─ 组 node-2 消费 → 本机只有 B 的 Channel → 给 B 推送
```

如果把两个节点放在同组，记录可能只由其中一个节点处理，而另一个节点持有的连接收不到推送。因此当前选择各节点独立消费，再按本机连接过滤。代价是节点多时重复解析、群聊成员查询等工作也增加，不是无成本扩展方案。

部署时不同节点需要不同 chat.node-id；默认值不能让所有实例都照抄。每个组能否读到某条历史记录，还取决于消费位点和保留情况；不应把独立消费组理解成无条件重放全部历史。

### 第八步：什么时候认为消费进度可以提交

打开 [application.yml](../../RealTimeService/src/main/resources/application.yml)，看 Kafka consumer 与 listener：

```yaml
consumer:
  auto-offset-reset: latest
  enable-auto-commit: false
listener:
  ack-mode: record
```

当前关闭 Kafka 客户端定时自动提交，由 Spring 的 RECORD 模式在监听方法正常处理返回后提交对应进度。方法抛出的异常交给容器错误处理，不可仅凭本类宣称所有失败都已完整补偿。参考 [Spring Kafka 3.3 的提交模式](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html)。

offset 是分区中的位置，提交值通常指下一条待处理位置，例如处理 offset=42 后提交 43。它与 Stream 逐条 XACK、Pending 列表的机制不完全一样。

重要失败窗口：writeAndFlush 是异步的，本方法没有等待写操作完成；连接不存在也会正常返回。因此消费位点可能已提交，但网络写随后失败。失败监听器只记日志，不会自动让已返回的 consume 再抛异常，也没有自动证明客户端收到。这是当前在线推送机制的边界。

latest 在没有有效已提交位点时决定从分区末尾开始；有有效位点则从已有进度恢复。不要把它解释为每次服务重启都跳过旧消息，也不要指望新组一定处理创建前的历史记录。offset 行为可对照上述 KafkaConsumer 文档。

## 关键设计和边界

- 当前消费者处理 message-topic 推送，不负责 store-topic 的存储。
- Kafka 生产者幂等不等于客户端重发、消费者重试和网络投递全链路只处理一次。
- 同组分摊、不同组独立读取，与“给 A 回显、给 B 推送”是两层机制；后者是 consume 内的业务代码明确执行的。
- 某节点查不到 Channel 只说明连接不在本节点；在线跨节点场景要结合消费组策略判断。
- 方法返回、消费进度提交、Channel 写成功、客户端收到、用户已读，是不同的确认位置。
- 本篇仅静态阅读，没有把文档中的示例当成实际 Kafka 联调结果；相对链接检查也不代表已验证界面点击。

## 本篇自查

1. consume 是客户端调用、Broker 直接调用，还是监听容器调用？payload 是什么？
2. 消息读完后是不是立即从 Kafka 删除？offset 与业务 messageId 相同吗？
3. 为什么单聊要分别调用两次 pushMessageToUser？
4. A、B 分别连接两个实时节点时，为什么当前使用两个消费组？
5. RECORD 模式提交进度，能否证明 B 收到消息？

## 自查解答

1. Spring Kafka 监听容器拉取记录后，通过适配器调用；payload 是记录 value 解码得到的消息 JSON。
2. 不是，记录由保留策略管理。offset 是分区位置，messageId 是项目的业务消息标识。
3. 一次给发送者回显，一次给不同的接收者推送；具体显示由客户端处理。
4. 同组会分摊分区，处理消息的节点可能没有目标连接；独立组让各节点各自处理，再仅推送本机连接。
5. 不能。当前异步网络写可能在方法返回后失败，客户端收到和已读还需要另外的确认机制。
