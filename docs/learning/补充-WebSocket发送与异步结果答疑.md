# 补充：WebSocket 发送、Feign 与异步结果

## 本篇目标

沿 A 给 B 发送一条消息的场景，理解 `writeAndFlush`、Feign、`CompletableFuture<SendResult<String, String>>`，并读懂 WebSocketHandler 后面的辅助方法和连接事件。本篇是已有代码答疑，不推进原文章节，不修改实现。

## 具体业务场景

A=1001、B=2002，两人通过 WebSocket 连接实时服务。A 在会话 99 发送“你好”。

```text
A → WebSocketHandler
    → Feign 请求 UserService：是否允许发送？
    → Kafka：分别投递存储、推送消息，取得异步结果
    → 推送消费者读取消息
    → 找到 A、B 的 Channel，writeAndFlush 发回客户端
```

心跳与错误响应可由 WebSocketHandler 直接写回当前连接，不经过推送消费者。

## 分步调用流程与源码入口

### 1. writeAndFlush：通过指定连接写出数据

入口：[WebSocketHandler.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java)，搜索 `writeAndFlush`。

这是 Netty 提供的方法；其接口声明位于 `io.netty.channel.ChannelOutboundInvoker`，Channel 和 ChannelHandlerContext 都暴露这一能力。可在 VS Code 对方法使用“转到定义”查看依赖声明。

```java
ctx.writeAndFlush(new TextWebSocketFrame("pong"));
```

`TextWebSocketFrame` 表示 WebSocket 文本帧。`write` 将数据交给出站处理流程、进入待发送队列；`flush` 请求将之前待发送的数据向底层传输推进。合起来通常理解为“通过这条连接发出去”。它不会等待对方客户端确认收到或已读；网络繁忙时也不保证立刻全部发送完成。

返回的 ChannelFuture 用来观察本次异步写操作的结果。它属于 Netty，与下面的 JDK CompletableFuture 是两种 API。

### 2. Feign：把方法调用转换成跨服务 HTTP 请求

入口：[UserServiceClient.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/client/UserServiceClient.java)，搜索 `canSend`；服务端入口是 [SessionAccessController.java](../../UserService/src/main/java/cn/qingliao/userservice/controller/SessionAccessController.java) 的同名方法。

`UserServiceClient` 是接口，OpenFeign 为它创建代理对象。调用 `users.canSend(...)`，代理按接口上的地址与参数注解构造 HTTP 请求，发到 UserService，再把响应转换为 boolean。

当前默认地址是 `http://localhost:8104`，请求路径是 `/api/user/internal/can-send`。例如参数为 userId=1001、sessionId=99、sessionType=0、receiverId=2002。接口检查用户、会话、成员关系等业务条件。

这里是同步调用，当前处理线程要等请求返回或失败；不是直接执行另一个进程里的 Java 方法，也不通过 Kafka。此类业务处理器被安排在业务执行器中，避免占用 Netty I/O 线程等待远程响应。

### 3. CompletableFuture：表示稍后完成的结果

`java.util.concurrent.CompletableFuture<T>` 是 JDK 类。它保存某项操作尚未完成、正常完成或异常完成的状态，正常完成时携带 T 类型的结果。

它本身不是线程，也不意味着声明它就启动了线程。在这里，Kafka 客户端负责异步发送，并在结果确定后完成返回的 Future。

```java
CompletableFuture<SendResult<String, String>> store =
        kafka.send(topic, key, payload);
```

从里往外读：

| 类型部分 | 含义 |
|---|---|
| 第一个 String | Kafka 消息 key 的类型；这里是会话 ID 字符串 |
| 第二个 String | Kafka 消息 value 的类型；这里是消息 JSON |
| SendResult<String, String> | Spring Kafka 提供的发送结果，包括发送记录、Topic、分区和 offset 等元数据 |
| CompletableFuture<...> | 这份发送结果可能尚未产生，可在完成后读取或处理异常 |

`store` 是结果句柄，不是把消息存进数据库的结果。

```java
CompletableFuture.allOf(store, push, ai)
        .whenComplete((result, error) -> {
            if (error != null) {
                // 向客户端提示投递失败
            }
        });
```

`allOf` 返回一个汇总 Future，等输入 Future 全部结束后完成；有异常时汇总结果为异常。其正常结果类型是 Void，所以这里的 result 不是三份 SendResult，而是 null。`whenComplete` 注册完成回调；error 为 null 表示汇总正常完成，否则是异常。

这里没有 get/join 来阻塞等待，但 Kafka send 自身在等待元数据或缓冲区时仍可能暂时阻塞。回调也不一定由新线程执行。

非 AI 会话使用 `completedFuture(null)`，表示 AI 分支无需工作，直接给出一个已经完成的结果，便于统一汇总。

### 4. 后续辅助方法

继续在 WebSocketHandler 中按下列入口阅读：

| 方法 | 职责与关键点 |
|---|---|
| send | 调用 kafka.send；若调用当场抛异常，用 failedFuture 包装成异常完成的结果，统一交给 allOf 处理。已发生的其他发送不会因此自动撤回。 |
| validate | 接受消息正文结构，检查会话、类型、接收者、内容和客户端消息标识，并统一 body。格式正确不代表有发送权限，权限由 canSend 检查。 |
| replyError | 在连接仍活动时，通过文本帧返回 error 和 clientMessageId。客户端据此关联发送失败的消息；此方法自身不关闭连接。 |
| userEventTriggered | 收到握手完成事件就绑定在线连接；收到读空闲事件就关闭连接；其他事件交给父类继续传播。 |
| channelInactive | 连接变为不活动时清理用户映射，然后调用父类传播连接失效事件。 |
| exceptionCaught | 记录未在前面处理掉的异常，关闭对应连接。 |

### 5. 握手完成分支中的回调不是立即执行

```java
ChannelManager.bind(userId, ctx.channel(), () -> {
    long disconnectedAt = System.currentTimeMillis();
    ctx.executor().execute(
            () -> connections.disconnected(userId, disconnectedAt));
});
```

外层 `() -> {...}` 是交给 ChannelManager 保存的断开回调，bind 当时不会立即执行它。连接清理且成功移除自己的映射后，才触发此回调。

回调记录断开时间，再向当前处理器的执行器提交保存离线时间的任务；该处理器配置了业务执行器，相关 Redis 工作不必直接在关闭回调所在的 I/O 线程中完成。旧连接若已被新连接替换，不应再把用户标记为离线。

## 关键设计和边界

- writeAndFlush 是连接写出；Feign 是同步远程 HTTP 调用；Kafka send 的 Future 是消息投递结果，三者不能混为一谈。
- allOf 只是汇总结果，不是事务。推送成功、存储分支投递失败等部分成功状态仍然可能发生。
- Kafka 投递成功不等于数据库写入成功，也不等于 B 收到或已读。
- 入站消息、用户事件、连接失效和异常是不同回调入口，不是整个类里的方法按源码从上到下依次执行。
- 本篇依据当前源码静态核对，没有启动服务或进行网络联调。与早期第 03 篇的阶段性讲义相比，当前代码已有会话授权、每帧凭证核对、AI 分支及离线时间回调等扩展。

## 本篇自查

1. writeAndFlush 成功是否证明 B 已读？
2. users.canSend 看起来是方法调用，为什么可能等待网络？
3. CompletableFuture 是线程吗？SendResult 的两个 String 表示什么？
4. bind 中传入的断开回调何时执行？allOf 能保证两路投递同时成功吗？

## 自查解答

1. 不能。它观察的是本机连接写操作，已读需要客户端业务确认。
2. users 是 Feign 代理，调用被转换为发往 UserService 的 HTTP 请求，并等待返回。
3. 不是线程，是异步完成状态与结果的载体。两个 String 分别是 Kafka key 和 value 的类型。
4. 在连接清理且成功移除自己的映射后触发。allOf 只汇总结果，不能提供原子提交或失败回滚。
