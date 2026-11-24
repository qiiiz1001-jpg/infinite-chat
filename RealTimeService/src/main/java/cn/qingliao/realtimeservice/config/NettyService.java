package cn.qingliao.realtimeservice.config;

import cn.qingliao.realtimeservice.utils.SnowflakeDynamicUtil;
import cn.qingliao.realtimeservice.websocket.WebSocketAuthHeader;
import cn.qingliao.realtimeservice.websocket.WebSocketHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class NettyService {
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final SnowflakeDynamicUtil ids;
    private final cn.qingliao.realtimeservice.client.UserServiceClient users;
    private final cn.qingliao.realtimeservice.websocket.ConnectionStateService connections;

    @Value("${netty.port:9101}")
    private int port;

    @Value("${netty.worker-threads:4}")
    private int workerThreads;

    @Value("${netty.business-threads:4}")
    private int businessThreads;

    @Value("${netty.reader-idle-seconds:60}")
    private int readerIdleSeconds;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private DefaultEventExecutorGroup business;
    private final DefaultChannelGroup channels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private Channel server;

    /** 绑定独立 WebSocket 端口，不在启动线程等待 closeFuture；绑定失败必须回收线程。 */
    @PostConstruct
    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup(workerThreads);
        business = new DefaultEventExecutorGroup(businessThreads);
        try {
            server =
                    new ServerBootstrap()
                            .group(boss, worker)
                            .channel(NioServerSocketChannel.class)
                            .childOption(ChannelOption.TCP_NODELAY, true)
                            .childHandler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel channel) {
                                            channels.add(channel);
                                            ChannelPipeline pipeline = channel.pipeline();
                                            pipeline.addLast(
                                                    new IdleStateHandler(readerIdleSeconds, 0, 0));
                                            // 解析http
                                            pipeline.addLast(new HttpServerCodec());
                                            // 聚合完整请求
                                            pipeline.addLast(new HttpObjectAggregator(65536));
                                            // Redis 与 Kafka 调用可能阻塞，放到独立执行器，避免阻塞 Netty I/O 线程。
                                            pipeline.addLast(
                                                    business,
                                                    "auth",
                                                    new WebSocketAuthHeader(redis));
                                            // 进行握手升级
                                            pipeline.addLast(
                                                    new WebSocketServerProtocolHandler(
                                                            "/ws/netty", "im", false, 65536));
                                            pipeline.addLast(new WebSocketFrameAggregator(65536));
                                            // 握手之后返回握手事件，给下面的处理器处理
                                            pipeline.addLast(
                                                    business,
                                                    "messages",
                                                    new WebSocketHandler(
                                                            kafka, ids, users, connections));
                                        }
                                    })
                            .bind(port)
                            .sync()
                            .channel();
            log.info("WebSocket 已监听 {}，路径 /ws/netty", server.localAddress());
        } catch (InterruptedException | RuntimeException e) {
            stop();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.close().awaitUninterruptibly();
        }
        channels.close().awaitUninterruptibly();
        if (business != null) {
            business.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
        if (worker != null) {
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }

    public int boundPort() {
        return ((java.net.InetSocketAddress) server.localAddress()).getPort();
    }
}
