# 补充：Kafka 结构图与概念总览

[返回学习导航](00-学习导航.md) · [接着读消费者源码](补充-Kafka消费与在线消息推送.md)

## 本篇目标

用文本结构图复习本次答疑，分清机器、JVM、微服务实例、Kafka Broker、客户端、Topic、分区、副本、消费组与线程，并解释用户重复发送和失败重试的区别。

本篇总结已有知识与源码，不推进原文章节、不修改业务实现。部署图、分配图和故障切换图是教学示例；当前项目声明的配置另行标明。本次没有启动 Kafka、增加服务实例或进行故障实验。

## 具体业务场景

A 给 B 发“你好”。A、B 各自与实时服务建立 WebSocket 连接。实时服务把消息交给 Kafka，再由消费者处理推送和存储。

```text
A 客户端
    |
    | WebSocket 文本消息
    v
实时服务：校验、补齐发送者和消息 ID
    |
    +---- 写 message-topic ----> 在线推送消费者 ----> A 回显、B 收消息
    |
    +---- 写 store-topic ------> 存储消费者 --------> 存储业务
```

用户客户端通过 WebSocket 收发；生产者和消费者是微服务中的 Kafka 客户端程序。Kafka Broker 保存、提供消息读取，推送与存储代码在微服务里执行。

## 1. 先看部署结构：机器、JVM、服务实例

### 1.1 一台机器可以运行多个 JVM

常规独立部署时，一份 Spring Boot 微服务实例运行在一个 JVM 进程中。下面是可以采用的部署示例，不代表当前机器已经启动这些进程。

```text
一台电脑 / 服务器
|
+-- JVM 进程 1：UserService 实例
|
+-- JVM 进程 2：RealTimeService 实例 1
|   +-- Netty 服务器
|   +-- 本机在线连接 Map
|   +-- Kafka 生产者客户端
|   +-- Kafka 消费者客户端
|
+-- JVM 进程 3：RealTimeService 实例 2
|   +-- Netty 服务器
|   +-- 另一份本机在线连接 Map
|   +-- Kafka 生产者客户端
|   +-- Kafka 消费者客户端
|
+-- JVM 进程 4：OfflineDataService 实例
|   +-- Kafka 消费者客户端
|
+-- JVM 进程 5：Kafka 服务端实例，称为 Broker
    +-- 分区日志及相关服务端状态
```

这里的“实时节点 1、2”指两份 RealTimeService 实例；“Kafka 节点”指 Broker。说明节点时要先说明是哪种服务，不能把二者混在一起。

多 JVM 可以在同一台机器，也可以分布在不同机器。JVM 并非语言规则上只能容纳一个应用，但本项目按常规独立进程方式理解即可。

### 1.2 微服务里的 Kafka 客户端不等于 Broker

```text
熟悉的 Redis：
微服务 JVM 中的 StringRedisTemplate --网络--> Redis 服务端

这里的 Kafka：
微服务 JVM 中的 KafkaTemplate ------网络--> Kafka Broker
微服务 JVM 中的 KafkaConsumer <-----网络--- Kafka Broker
                                  消费者主动发起拉取
```

引入 Kafka 依赖使微服务能创建客户端，不会因此在每个微服务 JVM 中启动一个 Broker。多份微服务可以连接同一套 Kafka 服务。

## 2. 再看数据结构：Topic、分区、offset

### 2.1 Topic 是逻辑分类，分区保存记录

```text
Kafka 中的逻辑 Topic：message-topic
|
+-- 分区 P0：[offset=0, M1] [offset=1, M4] ...
+-- 分区 P1：[offset=0, M2] [offset=1, M5] ...
+-- 分区 P2：[offset=0, M3] [offset=1, M6] ...
```

一条 Kafka 记录写入某 Topic 的一个分区。offset 是分区内部的位置，不是全局消息编号；P0 的 offset=0 与 P1 的 offset=0 可以同时存在。业务 messageId 则是应用自己生成的消息标识。

Topic 是逻辑概念，其分区日志由 Broker 保存。一个 Broker 可以保存多个分区；多个 Broker 时，分区也可以分散放置。

```text
示例 A：单 Broker，三个分区
Broker 1
  +-- message-topic / P0
  +-- message-topic / P1
  +-- message-topic / P2

示例 B：三个 Broker，暂只画每个分区的一份数据
Broker 1                  Broker 2                  Broker 3
  +-- message-topic/P0      +-- message-topic/P1      +-- message-topic/P2
```

Topic、分区、键与保留机制可对照 [Kafka 官方入门](https://kafka.apache.org/41/getting-started/introduction/)。

### 2.2 一条业务消息可以生成两条不同用途的 Kafka 记录

本项目 [WebSocketHandler.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java) 分别发送存储与推送 Topic：

```text
业务消息：messageId=M100，内容“你好”
    |
    +-- send("message-topic", key, payload)
    |     +-- 一条推送记录，例如 message-topic/P1/offset=42
    |
    +-- send("store-topic", key, payload)
          +-- 一条存储记录，例如 store-topic/P2/offset=18
```

上面的分区、offset 只是示例。这两条记录可以携带同一个业务 messageId，承担不同工作；它们不是分区副本，也不能根据业务 ID 相同就判断发送错误。两次发送独立，不保证同时成功。

## 3. 分区有什么意义

### 3.1 分散存储和读写压力

分区使一个 Topic 的数据有机会分布到多台 Broker，利用多台机器的资源。只有一个 Broker 时，多个分区也能存在，但不会凭空增加物理机器。

### 3.2 让同组消费者并行工作

在本项目使用的普通消费组模式下，同一个分区同一时间分配给组内一个消费者；一个消费者可以负责多个分区。

```text
一个组 G，三个分区、三个消费者：
P0 ---> 消费者 C1
P1 ---> 消费者 C2
P2 ---> 消费者 C3

同一个组 G，再增加 C4：
P0 ---> C1
P1 ---> C2
P2 ---> C3
        C4：没有分区可分配

同一个组 G，只有一个消费者：
P0 --+
P1 --+--> C1 负责三个分区
P2 --+
```

这是说明正常分配关系，不保证故障、重试或重新分配后绝不重复处理。不同消费组可以各自读取这些分区。

### 3.3 给同一会话提供顺序处理的基础

当前生产者以 sessionId 字符串作为 key。在当前分区策略、分区数保持不变的条件下，相同 key 进入同一分区。

```text
假设会话 99 的 key 对应 P1：
会话 99：“你好”   ---+
会话 99：“明天见” ---+---> P1：按实际追加顺序记录

假设会话 88 的 key 对应 P2：
会话 88：“出发了” -------> P2：可以独立处理
```

分区保证读取其追加顺序，不提供所有分区的全局顺序，也不自动解决多个客户端并发发送时的业务先后问题。

## 4. 消费组如何组织消费者

### 4.1 消费组由 groupId 标识

消费组是参与分摊读取工作的消费者集合，共同使用按 Topic/分区记录的一套组消费进度。它不是一台机器、一个 JVM、一种消息类型或一个 Java 线程。

```text
同组可以跨 JVM：
实时节点 1 / JVM 1：消费者 C1，groupId=G --+
                                        +--> 消费组 G，协作分摊分区
实时节点 2 / JVM 2：消费者 C2，groupId=G --+

同一个 JVM 也可以有不同组的消费者：
实时节点 1 / JVM 1
  +-- 消费者 C1，groupId=G1
  +-- 消费者 C2，groupId=G2
```

同组关系看 groupId，不看是否运行在同一机器或进程。消费者职责由订阅和业务代码决定；一个组可以涉及多个 Topic，同一个 Topic 也可以被多个组分别读取。

### 4.2 同组分摊，不同组独立读取

```text
message-topic
  P0 ----+
  P1 ----+----> 消费组 G1：C1、C2 分摊这些分区
  P2 ----+
         |
         +----> 消费组 G2：C3 独立读取这些分区
```

G1、G2 分别维护进度。在正常读取范围内，同一条记录可以被 G1 处理一次、G2 再处理一次。这是有意的多组处理，不是 Broker 错误地多存一条消息。

独立读取不表示自动重放全部历史：记录是否仍保留、组的已提交位点、首次读取策略都会影响可读范围。

消费组、分区分配、offset 与线程安全说明见 [KafkaConsumer 官方文档](https://kafka.apache.org/39/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)。

### 4.3 这次的疑点：每组各存一份消息，还是只保存自己的进度？

**同一份 Topic 分区日志，多个组各自有书签。**“广播”描述各组可独立处理的效果，不表示给每组复制一份消息。

```text
message-topic / P0：一份消息日志
    +-- G1：已提交 43 -> 下次从 43 继续
    +-- G2：已提交 18 -> 下次从 18 继续
```

进度按 `groupId + Topic + 分区` 区分，由消费者或 Spring Kafka 提交，Kafka 服务端保存在内部 Topic `__consumer_offsets`。同组共享这套分区进度，不同组互不影响；各组已经处理的范围可以不同。

消费者当前读到的位置和已提交位置也不同：业务处理了 offset=42，但还没成功提交 43 就宕机，之后可能重读 42，所以存储仍需要 messageId 幂等。

## 5. 消费线程和消费者对象的关系

一个应用可以有多个消费者对象。通常每个 KafkaConsumer 由自己的消费线程操作，避免多个线程并发操作同一非线程安全对象。

以下是帮助理解的配置示例，当前 ConsumerMessageService 并没有显式写 concurrency=3：

```java
@KafkaListener(
        topics = "message-topic",
        groupId = "example-group",
        concurrency = "3")
public void consume(String payload) {
    // 业务处理
}
```

Spring Kafka 可据此创建三个并行的子消费容器，每个使用自己的消费者及消费线程：

```text
一个微服务实例 / 一个 JVM
|
+-- Kafka 监听容器，concurrency=3
    +-- 子容器 1：消费线程 T1 -> KafkaConsumer C1 --+
    +-- 子容器 2：消费线程 T2 -> KafkaConsumer C2 --+--> 同组 example-group
    +-- 子容器 3：消费线程 T3 -> KafkaConsumer C3 --+
```

能否同时处理还受可分配分区数量限制。三个分区不会自动给项目创建三个线程；concurrency 决定的消费者并发数也不等于 Netty 的 worker-threads。

如果只让一个消费者拉取，再自行创建业务线程处理任务，业务线程并不会因此成为新的消费组成员：

```text
Kafka 分区 ---> KafkaConsumer C1 ---> 业务任务队列
                                     +--> 工作线程 W1
                                     +--> 工作线程 W2
                                     +--> 工作线程 W3

这里仍只有 C1 一个 Kafka 消费者成员。
```

这是另一种自行管理并发、顺序和提交进度的方案，本篇不向当前工程增加该实现。监听并发与容器说明见 [Spring Kafka 监听注解](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/listener-annotation.html) 和 [监听容器](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html)。

## 6. 为什么本项目每个实时节点用不同消费组

当前 [ConsumerMessageService.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/consumer/ConsumerMessageService.java) 的配置是：

```java
@KafkaListener(
        topics = CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH,
        groupId = "infinite-chat-push-${chat.node-id:node-1}")
```

**这次的疑点：组号会在启动时自动变吗？** 不会。前缀固定，后缀读取 `chat.node-id`；[application.yml](../../RealTimeService/src/main/resources/application.yml) 又从环境变量 `CHAT_NODE_ID` 取这个值，不设置就默认 `node-1`。

```text
实例 A：CHAT_NODE_ID=node-1 -> infinite-chat-push-node-1
实例 B：CHAT_NODE_ID=node-2 -> infinite-chat-push-node-2
两份都不设置                -> 都是 infinite-chat-push-node-1，会进入同组
```

按当前推送方案：只启动一份可用默认值；同时启动多份 RealTimeService，要分别配置不同节点 ID；同一实例重启通常沿用原值。配置按**服务实例**区分，同一台机器启动两份也要区分。其他需要分摊任务的业务，则可以有意使用相同 groupId。

下面假设两组都已就绪，消息在其读取范围内，A、B 分别连接两份实时服务。

```text
A 客户端 <----WebSocket----> 实时节点 1 / JVM 1
                            本机 Map：1001 -> Channel A
                            消费组：infinite-chat-push-node-1

B 客户端 <----WebSocket----> 实时节点 2 / JVM 2
                            本机 Map：2002 -> Channel B
                            消费组：infinite-chat-push-node-2

Kafka 中同一条推送记录：A=1001，B=2002
    |
    +--> 组 node-1 读取 -> 节点 1 查本机 Map -> 给 A 回显
    |
    +--> 组 node-2 读取 -> 节点 2 查本机 Map -> 给 B 推送
```

若两节点放在同组，则可能只由持有 A 连接的节点处理这一条记录，B 所在节点没有读到，便无法推送。本项目选择各节点独立组，各自读取后仅发送给本机连接。它会增加重复解析和查询等工作，节点越多并非成本越低。

不是每个节点都要具有所有业务类型的消费者：

```text
RealTimeService 实例
  +-- message-topic 消费任务：在线推送

OfflineDataService 实例
  +-- store-topic 消费任务：存储业务

各服务按职责订阅；一个服务也可以添加其他所需的监听任务。
```

## 7. 副本的意义：Broker 宕机后的容错

### 7.1 分区拆数据，副本复制同一分区的数据

```text
分区 P0 的两份副本：
Broker 1：P0 Leader   [M1][M2][M3]
                       | 同步
Broker 2：P0 Follower [M1][M2][M3]

这是同一分区的两份数据，不是两个不同分区各存一条新记录。
```

单条记录通常由分区 Leader 接受写入，再复制到 Follower。同一分区的副本不作为多份独立记录重复交给正常消费者。

```text
一个三 Broker、每分区两副本的放置示例：

             Broker 1          Broker 2          Broker 3
P0           Leader            Follower         --
P1           --                Leader           Follower
P2           Follower          --               Leader
```

### 7.2 Leader 所在 Broker 宕机，可以选择新的 Leader

```text
原状态：Broker 1 持有 P0 Leader，Broker 2 持有同步的 P0 Follower
    |
    | Broker 1 宕机
    v
存在符合条件的存活同步副本，且集群协调正常
    |
    v
Broker 2 的 P0 副本被选为新 Leader，继续服务
```

副本提升容错能力，无法保证任意故障组合都不丢数据或始终可用；这还与同步状态、确认配置和可用 Broker 等条件有关。这里只解释正常的同步副本切换思路。

### 7.3 当前工程声明的是三个分区、每分区一份数据

打开 [KafkaTopicConfig.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/config/KafkaTopicConfig.java)：

```java
.partitions(3)
.replicas(1)
```

```text
当前代码的期望配置：
P0：总计 1 份 -> 主副本 1 份，额外跟随副本 0 份
P1：总计 1 份 -> 主副本 1 份，额外跟随副本 0 份
P2：总计 1 份 -> 主副本 1 份，额外跟随副本 0 份

replicas(3) 才表示每分区总计 3 份：1 主 + 2 跟随副本。
```

这是源码声明，未连接 Broker 核对已经存在的实际 Topic。新增每分区三副本的 Topic 需要至少三个可放置副本的 Broker。不能仅凭代码中的 acks=all，就把单副本环境说成具有多副本容错。

## 8. 内容相同、分区副本、业务重试不是同一回事

### 8.1 用户主动发送两次，应当是两条业务消息

```text
用户第一次点击发送“你好”
  -> clientMessageId=c1 -> 服务端 messageId=m1

用户第二次点击发送“你好”
  -> clientMessageId=c2 -> 服务端 messageId=m2

这是两次业务操作。内容相同，也应保留两条。
```

### 8.2 同一次发送因为超时而重试，业务上希望只产生一条

```text
用户只点击一次发送
    |
    v
客户端发送 c1 -> 服务端处理成功，生成 m1
    |                         |
    |                         +-- 返回结果在途中丢失
    v
客户端不知道是否成功，重试同一操作，仍使用 c1
    |
    +-- 有业务幂等：识别 c1 已处理，关联已有 m1
    |
    +-- 没有业务幂等：再次处理并生成 m2，形成一次操作的重复结果
```

clientMessageId 的复用是重试协议约定，需要客户端配合。按本次核对的 WebSocketHandler，每次通过检查后都会调用 ids.nextId；clientMessageId 用于关联回显，但该入口没有按它做业务去重。因此不能将上图的幂等分支写成当前已实现能力。

### 8.3 消费重试与生产者协议重试也要分清

```text
应用再次 send：可能创建一条新的 Kafka 记录
Kafka 生产者协议层重试：配置幂等生产可处理相应协议重复
消费者重试：可能是对同一条 Kafka 记录再次执行业务
分区副本同步：复制同一条记录，提供容错
不同消费组读取：同一记录交给不同的组各自处理
```

生产者幂等不覆盖所有客户端重发和消费侧副作用。业务去重应根据同一次操作的标识设计，不能简单按“文本内容相同”去重。

## 9. 对照项目，把这些概念放回调用流程

```text
A 客户端
    |
    v
RealTimeService：WebSocketHandler.channelRead0
    +-- 从 Channel 取可信 userId
    +-- 校验当前凭证、消息结构与发送权限
    +-- 生成 messageId、createdTime
    +-- 使用 sessionId 字符串作为 Kafka key
    |
    v
KafkaTemplate -> 网络 -> Broker -> message-topic 的某个分区
    |
    v
对应消费组的 KafkaConsumer 拉取记录
    |
    v
Spring Kafka 监听容器 -> ConsumerMessageService.consume(payload)
    +-- 解析消息、创建响应
    +-- 单聊确定 A、B；群聊查询成员
    +-- ChannelManager 查本机在线连接
    +-- writeAndFlush 发出 WebSocket 文本帧
```

注意 WebSocketHandler 的实际代码先检查连接凭证，再处理正文；图中的身份相关条目用于说明职责，不代表另写了一套业务执行顺序。

| 要确认的关系 | 打开的源码 | 搜索入口 |
|---|---|---|
| 生产什么消息、key 是什么 | [WebSocketHandler.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java) | channelRead0、send |
| Topic、分区数、副本数 | [KafkaTopicConfig.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/config/KafkaTopicConfig.java) | pushTopic |
| 消费哪个 Topic、属于哪个组 | [ConsumerMessageService.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/consumer/ConsumerMessageService.java) | @KafkaListener、consume |
| 怎么找到用户连接 | [ChannelManager.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/ChannelManager.java) | getChannelByUserId |
| 节点 ID、反序列化与提交模式 | [application.yml](../../RealTimeService/src/main/resources/application.yml) | chat.node-id、spring.kafka |

## 关键设计和边界

- 机器包含进程，常规微服务实例各有 JVM；Broker 是独立服务，Kafka 客户端在业务服务内部。
- 记录归属某 Topic 的一个分区；分区副本可以跨 Broker，消费组可以独立读取相同记录。
- 分区、消费线程、实时节点、消费组是不同维度，彼此没有固定的一对一关系。
- 当前推送按节点独立消费组，再按本机 Channel 过滤；每个节点不需要运行所有业务消费者。
- 当前推送消费返回后可以提交进度，但 writeAndFlush 的异步失败仍可能稍后发生；提交进度不等于客户端收到或已读。
- 本文仅完成资料整理、源码核对和本地链接静态检查，没有修改 Java、配置、SQL，也没有进行 Kafka 实机验证。静态链接检查不代表已验证 VS Code 点击。

## 本篇自查

1. 一个微服务实例内有 KafkaTemplate，就代表其 JVM 内运行了 Kafka Broker 吗？
2. 一条记录进入 P1，是否只能被一个实时节点处理？
3. 同组有三个分区、四个消费者，正常分配时能否四个消费者同时各负责一个分区？
4. 同 JVM 的两个消费者一定同组吗？加三个业务工作线程就多了三个组成员吗？
5. replicas(1) 是否表示一主一备？
6. 两次主动发送同样内容，与同一次发送自动重试应该怎样区分？

## 自查解答

1. 不代表。KafkaTemplate 是客户端，通常通过网络连接独立 Broker。
2. 不是。多个消费组可以各自读取 P1；同组正常情况下该分区由一个消费者负责。
3. 不能，最多三个消费者分到分区；一个消费者也可以负责多个分区。
4. 同组由 groupId 判断；业务工作线程不是 KafkaConsumer，不自动增加组成员。
5. 不是。副本数包含主副本，总计只有一份，没有额外跟随副本。
6. 主动发送两次应有不同操作标识并保留两条消息；同次操作重试复用约定的操作标识，业务幂等识别后应关联同一结果，不能按内容是否相同判断。
