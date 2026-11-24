# 补充：WebSocket 握手与协议升级

## 本篇目标

沿 A 连接实时服务的完整过程，理解握手、HTTP 升级、Channel 的建立时机，以及 HandshakeComplete 如何触发在线登记。本篇为源码答疑，不推进原文章节，不修改业务实现。

## 具体业务场景

A 已登录，持有 Access Token，使用支持自定义握手请求头的客户端连接 `ws://localhost:9101/ws/netty`。

```text
建立 TCP 连接，Netty 接受连接并初始化 Channel
    ↓
客户端发送 HTTP/1.1 WebSocket 升级请求
    ↓
HTTP 解码、请求聚合 → 项目的 Token 鉴权
    ↓
Netty 验证 WebSocket 握手字段并返回 101 升级响应
    ↓
Netty 调整协议编解码处理，产生 HandshakeComplete 事件
    ↓
项目登记 userId → Channel
    ↓
客户端确认握手成功，通过同一连接发送 WebSocket 帧
```

## 分步调用流程与文件入口

下面按运行时间排列。项目代码的片段只摘取当前讨论的关键语句；框架内部以方法入口说明，不将它冒充为项目自定义方法。

### 第一步：应用启动，准备监听和新连接初始化规则

打开 [NettyService.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/config/NettyService.java)，搜索 `start`。

`@PostConstruct` 使 Spring 在组件初始化时调用 start。该方法创建 Boss、Worker、业务执行器，配置 ServerBootstrap，最后通过 `bind(port).sync().channel()` 绑定监听端口并取得服务端监听 Channel。

其中 childHandler 登记了一个 ChannelInitializer，规定接受新客户端连接时调用 initChannel。登记这条规则不代表启动时已有客户端连接，也不会把聊天消息当成新连接反复初始化。

### 第二步：客户端发起连接和 HTTP 升级请求

客户端库负责建立 TCP 连接并发送 WebSocket 开启握手请求。本项目的一个真实代码入口是 [NettySocketTest.java](../../RealTimeService/src/test/java/cn/qingliao/realtimeservice/websocket/NettySocketTest.java)，搜索 `newWebSocketBuilder`：

```java
client.newWebSocketBuilder()
        .header("Authorization", "Bearer " + token1)
        .buildAsync(uri, sender)
        .get(5, TimeUnit.SECONDS);
```

这是已有测试中的客户端代码；本次只阅读，未重新运行。uri 指向测试启动的 Netty 端口，路径为 `/ws/netty`。buildAsync 发起建连和握手；get 是测试等待连接结果，和服务端登记在线 Map 是不同位置的代码。

升级请求带 Upgrade、Connection、WebSocket 版本与随机 Key 等字段；Authorization 是本项目附加的登录凭证。浏览器原生 WebSocket API 不能随意设置该请求头。

### 第三步：服务端接受连接，创建 Channel 并安装 Pipeline

回到 NettyService 的 `initChannel(SocketChannel channel)`。Netty 接受 A 的连接后调用它，此时 channel 就是 A 的服务端连接对象。

```java
channels.add(channel);
ChannelPipeline pipeline = channel.pipeline();
pipeline.addLast(new IdleStateHandler(readerIdleSeconds, 0, 0));
pipeline.addLast(new HttpServerCodec());
pipeline.addLast(new HttpObjectAggregator(65536));
pipeline.addLast(business, "auth", new WebSocketAuthHeader(redis));
pipeline.addLast(new WebSocketServerProtocolHandler("/ws/netty", null, false, 65536));
pipeline.addLast(new WebSocketFrameAggregator(65536));
pipeline.addLast(business, "messages", new WebSocketHandler(kafka, ids, users, connections));
```

addLast 安装的是后续处理规则。每个客户端有自己的 Channel 和 Pipeline；此时没有把 A、B 的连接绑定到一起。

### 第四步：HTTP 解码和聚合，把握手请求交给项目鉴权

网络字节经 HttpServerCodec 解析、HttpObjectAggregator 聚合后，以 FullHttpRequest 形式进入 [WebSocketAuthHeader.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketAuthHeader.java) 的 `channelRead`。

```java
String token = request.headers().get(HttpHeaderNames.AUTHORIZATION);
if (token != null && token.startsWith("Bearer ")) {
    token = token.substring(7);
}
Claims claims = JwtUtil.parse(token);
String userId = claims == null ? null : claims.getSubject();
```

JWT 验签和过期检查位于 [JwtUtil.java](../../Common/src/main/java/cn/qingliao/common/utils/JwtUtil.java) 的 parse。处理器还检查 userId 格式、long 范围，并查询 `access:token:{userId}` 与请求 Token 比较。

校验失败则 reject 返回 HTTP 错误、释放请求并关闭连接；不会进入正常升级流程。

### 第五步：保存连接身份，继续把 HTTP 请求交给框架

WebSocketAuthHeader 校验通过后执行：

```java
ChannelManager.authenticate(ctx.channel(), userId);
ChannelManager.token(ctx.channel(), token);
ctx.pipeline().remove(this);
ctx.fireChannelRead(msg);
```

前两句只设置 Channel 属性；remove 只移除当前连接上的鉴权处理器；fireChannelRead 将本次 HTTP 请求继续往后传。这一步既不发送 101，也没有把连接登记进在线用户 Map。

### 第六步：Netty 内部完成协议升级

项目只需要安装 WebSocketServerProtocolHandler。其 `handlerAdded` 会自动在前面插入内部的 WebSocketServerProtocolHandshakeHandler。因此完整处理链还包括框架自行加入的处理器，不能只数项目里 addLast 的几项。

Netty 4.1.131.Final 的相关入口依次是：

```text
WebSocketServerProtocolHandler.handlerAdded
    → 自动加入 WebSocketServerProtocolHandshakeHandler
    → 该处理器的 channelRead 收到握手 HTTP 请求
    → WebSocketServerHandshakerFactory.newHandshaker 选择握手实现
    → WebSocketServerHandshaker.handshake 执行升级
```

握手实现生成成功响应，调整当前 Channel 的 Pipeline：移除 HTTP 聚合器、安装 WebSocket 编解码器，使用 HTTP 编码发送 101 响应，成功后移除原 HTTP 编解码器。之后同一连接按 WebSocket 帧处理数据。

框架源码依据：[协议处理器](https://github.com/netty/netty/blob/netty-4.1.131.Final/codec-http/src/main/java/io/netty/handler/codec/http/websocketx/WebSocketServerProtocolHandler.java)、[内部握手处理器](https://github.com/netty/netty/blob/netty-4.1.131.Final/codec-http/src/main/java/io/netty/handler/codec/http/websocketx/WebSocketServerProtocolHandshakeHandler.java)、[握手实现基类](https://github.com/netty/netty/blob/netty-4.1.131.Final/codec-http/src/main/java/io/netty/handler/codec/http/websocketx/WebSocketServerHandshaker.java)。这里按项目依赖版本核对，而不是按最新版推测。

### 第七步：框架发出事件，项目登记在线连接

Netty 的内部握手处理器监听握手 Future，成功后通过 fireUserEventTriggered 发出 HandshakeComplete。

事件沿 Pipeline 进入 [WebSocketHandler.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java) 的 `userEventTriggered`。它取出 Channel 上的 userId，再调用 ChannelManager.bind，并注册将来断开后保存离线时间的回调。

打开 [ChannelManager.java](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/ChannelManager.java)，搜索三个参数的 bind：

```java
channel.attr(ON_DISCONNECT).set(onDisconnect);
channel.attr(USER_ID).set(userId);
Channel old = USER_CHANNEL_MAP.put(userId, channel);
channel.closeFuture().addListener(future -> unbind(channel));
if (old != null && old != channel) {
    old.close();
}
```

A=1001 时，核心结果是 `1001 → Channel A`。这只是对已有 Channel 做在线登记；同时处理旧连接替换、注册关闭清理。断开回调此刻仅保存，不立即执行。

### 第八步：握手之后发消息，进入另一个入口

客户端确认握手成功后发送 WebSocket 文本帧；框架解码、必要时聚合分片，然后触发 WebSocketHandler 的 `channelRead0`。这里才处理 ping、聊天 JSON、发送权限和 Kafka 投递。

服务端事件回调与客户端收到成功结果分别由两端调度；流程图表达逻辑依赖，不保证两端回调的绝对先后。进入 Kafka 是后续消息业务，并非握手的组成步骤。

后续消息进入 Kafka 后，继续看[补充：Kafka 消费与在线消息推送](补充-Kafka消费与在线消息推送.md)。

### 第九步：文本消息与超时事件如何进入不同的方法

两者都由当前连接的 WebSocketHandler 处理，但走不同的回调入口。超时检测由前面的 IdleStateHandler 完成；WebSocketHandler 处理它产生的读空闲事件。

| 输入或状态 | 触发者 | WebSocketHandler 的入口 |
|---|---|---|
| 聊天文本、项目约定的文本 ping | 网络读取，经 WebSocket 解码得到 TextWebSocketFrame | channelRead0 |
| 读空闲超时 | IdleStateHandler 内部定时检查 | userEventTriggered |
| WebSocket 握手完成 | Netty 内部握手处理器 | userEventTriggered |
| 连接失效 | Netty 连接状态变化 | channelInactive |

项目通过 `pipeline.addLast(business, "messages", new WebSocketHandler(...))` 注册处理器。省略号代表当前文件中的四项构造器依赖；它只用于说明注册关系，不是另一个可运行构造器。

**文本的调用路径：**

```text
网络数据进入 Pipeline
    → WebSocket 解码、聚合，得到 TextWebSocketFrame
    → Netty 调用处理器的 channelRead
    → 继承的 SimpleChannelInboundHandler.channelRead 检查类型
    → 类型匹配，调用子类重写的 channelRead0
```

调用 channelRead0 的代码在 Netty 父类中，所以项目里通常找不到手动调用这个方法的语句。泛型 TextWebSocketFrame 约束这里需要处理的消息类型；文本 ping 和聊天 JSON 都先进入此入口，再由业务代码区分内容。参考 [SimpleChannelInboundHandler 对应版本源码](https://github.com/netty/netty/blob/netty-4.1.131.Final/transport/src/main/java/io/netty/channel/SimpleChannelInboundHandler.java)。

**超时的调用路径：**

```text
IdleStateHandler 安排读空闲检查任务
    → ReaderIdleTimeoutTask.run 检查上次读取时间
    → 达到读空闲条件，创建 IdleStateEvent
    → channelIdle 调用 ctx.fireUserEventTriggered(event)
    → 事件沿 Pipeline 传播
    → Netty 调用 WebSocketHandler.userEventTriggered
    → READER_IDLE 分支调用 ctx.close
    → 连接失效，channelInactive 清理映射
```

这个事件由服务端定时任务产生，不需要等客户端再发一条消息。定时任务通过 Netty 执行器调度，也不是为每条连接专门创建一个阻塞等待 60 秒的线程。参考 [IdleStateHandler 对应版本源码](https://github.com/netty/netty/blob/netty-4.1.131.Final/handler/src/main/java/io/netty/handler/timeout/IdleStateHandler.java)。

Netty 的 AbstractChannelHandlerContext 分别实现 fireChannelRead 和 fireUserEventTriggered 的后续分发，最终调用处理器对应的 channelRead 或 userEventTriggered；它并非扫描 Java 方法名或根据文本内容猜测调用哪一个方法。参考 [处理器上下文对应版本源码](https://github.com/netty/netty/blob/netty-4.1.131.Final/transport/src/main/java/io/netty/channel/AbstractChannelHandlerContext.java)。

### 第十步：区分事件种类、消息对象类型与消息内容

不能把所有回调理解成“客户端发来不同消息类型，父类按类型调用所有不同方法”。这里有三层判断：

1. Netty 按发生的事情分发：收到数据走 channelRead，用户事件走 userEventTriggered，连接活动/失效走 channelActive/channelInactive，异常走 exceptionCaught。后几种常由框架发现或产生，不要求客户端发送相应消息。
2. 对数据读取，SimpleChannelInboundHandler 的 channelRead 按泛型约束匹配消息对象；当前匹配 TextWebSocketFrame 时才调用子类 channelRead0，不匹配则继续传播。它不会收到二进制帧就自动寻找 channelRead1。
3. 在项目回调中，业务代码再判断内容：channelRead0 区分文本 ping 与聊天 JSON；userEventTriggered 用 instanceof 区分 HandshakeComplete 与 IdleStateEvent。

| 数据对象 | 表示什么 | 本项目中的处理位置 |
|---|---|---|
| FullHttpRequest | 完整 HTTP 请求 | 握手阶段的 WebSocketAuthHeader |
| TextWebSocketFrame | WebSocket 文本帧 | WebSocketHandler.channelRead0 |
| BinaryWebSocketFrame | WebSocket 二进制帧 | 当前业务处理器的文本泛型不匹配该类型 |
| PingWebSocketFrame / PongWebSocketFrame | 协议级心跳控制帧 | Netty 协议处理器 |
| CloseWebSocketFrame | 协议级关闭控制帧 | Netty 协议处理器 |

项目的字符串 "ping" 放在 TextWebSocketFrame 中，与协议级 PingWebSocketFrame 不同。握手完成、读空闲则是本机事件对象，也不是聊天 JSON 中写一个 type 就能伪造的事件。

可在子类重写 Netty 规定的回调入口来补充逻辑；如果自己起一个与接口、父类无关的方法名，Netty 不会自动调用它。这里用 @Override 帮助编译器检查是否真正重写了父类方法。

### 一张方法调用地图

```text
服务端预先启动：NettyService.start → 绑定端口
客户端发起：newWebSocketBuilder → buildAsync
    ↓ 网络连接到达服务端
Netty 接受连接 → NettyService.initChannel → 安装 Pipeline
    ↓ HTTP 升级请求
HttpServerCodec → HttpObjectAggregator
    → WebSocketAuthHeader.channelRead
        → JwtUtil.parse + Redis 对比
        → ChannelManager.authenticate / token
        → fireChannelRead
    → Netty 内部握手处理器.channelRead
        → handshaker.handshake → 调整编解码、响应 101
        → fireUserEventTriggered(HandshakeComplete)
    → WebSocketHandler.userEventTriggered
        → ChannelManager.bind → USER_CHANNEL_MAP.put

握手后客户端发送文本帧
    → WebSocketHandler.channelRead0 → 聊天消息业务
```

## 关键设计和边界

- TCP 建连、项目身份认证、WebSocket 协议升级、在线登记各有不同职责。
- A、B 各自与服务器握手，分别拥有连接与 Channel。
- 握手完成后，连接可连续发送多条消息，每条聊天消息使用 WebSocket 帧。
- 本篇依据当前工程、已有测试客户端以及 Netty 4.1.131.Final 框架源码静态核对，未启动服务、重新运行测试或抓包；请求与时序为说明性示例。只修改讲义，文档链接静态检查不代表已验证 VS Code 界面点击。

## 本篇自查

1. WebSocket 握手就是 TCP 三次握手吗？
2. 升级后是否需要新建 Channel、切换端口？
3. 谁完成协议升级，谁负责登记在线用户？
4. 安装 WebSocketServerProtocolHandler 后，为什么还有一个项目没有显式 new 的内部握手处理器？
5. 没有客户端消息进入时，为什么仍会执行 userEventTriggered？谁调用 channelRead0？
6. 客户端发送 JSON 字段 type，能不能决定调用 channelInactive 或 userEventTriggered？

## 自查解答

1. 不是。TCP 先建立连接，WebSocket 再进行应用层开启握手。
2. 本项目的 HTTP/1.1 升级流程复用原连接、Channel 和端口，切换后续协议处理。
3. Netty 的协议处理器及其内部握手实现完成升级；WebSocketHandler 收到握手完成事件后，调用 ChannelManager.bind 登记在线映射。
4. 该框架处理器在 handlerAdded 中自动插入内部握手处理器；addLast 是配置处理链，框架还可能进一步调整处理链。
5. IdleStateHandler 的定时任务可以独立产生读空闲事件，再经 Pipeline 分发。channelRead0 由继承的 SimpleChannelInboundHandler.channelRead 在消息类型匹配时调用。
6. 不能。JSON type 是业务数据，先作为文本帧进入 channelRead0；连接生命周期和框架事件走独立的分发路径。
