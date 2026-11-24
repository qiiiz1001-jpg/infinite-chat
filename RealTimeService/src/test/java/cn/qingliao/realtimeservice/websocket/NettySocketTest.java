package cn.qingliao.realtimeservice.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.utils.JwtUtil;
import cn.qingliao.realtimeservice.config.NettyService;
import cn.qingliao.realtimeservice.consumer.ConsumerMessageService;
import cn.qingliao.realtimeservice.utils.SnowflakeDynamicUtil;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.*;

/** 使用真实本机 TCP/WebSocket；只有 Redis/Kafka 被替换，验证完整 Pipeline 的线程切换与握手。 */
class NettySocketTest {
    @Test
    @SuppressWarnings("unchecked")
    void authenticatedSocketsExchangeHeartbeatAndChatAndServerReleasesPort() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        BlockingQueue<String> pushed = new LinkedBlockingQueue<>();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            if (CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH.equals(
                                    call.getArgument(0))) {
                                pushed.add(call.getArgument(2));
                            }
                            return CompletableFuture.completedFuture(null);
                        });
        String token1 = JwtUtil.generate("1001", 5, TimeUnit.MINUTES);
        String token2 = JwtUtil.generate("2002", 5, TimeUnit.MINUTES);
        when(values.get(CommonConstant.ACCESS_TOKEN_PREFIX + "1001")).thenReturn(token1);
        when(values.get(CommonConstant.ACCESS_TOKEN_PREFIX + "2002")).thenReturn(token2);
        var users = mock(cn.qingliao.realtimeservice.client.UserServiceClient.class);
        when(users.canSend(anyLong(), anyLong(), anyInt(), nullable(Long.class))).thenReturn(true);
        NettyService server =
                new NettyService(
                        redis,
                        kafka,
                        new SnowflakeDynamicUtil(1, 1),
                        users,
                        new ConnectionStateService(redis));
        ReflectionTestUtils.setField(server, "port", 0);
        ReflectionTestUtils.setField(server, "workerThreads", 2);
        ReflectionTestUtils.setField(server, "businessThreads", 2);
        ReflectionTestUtils.setField(server, "readerIdleSeconds", 60);
        WebSocket first = null;
        WebSocket second = null;
        int port;
        server.start();
        port = server.boundPort();
        try {
            URI uri = URI.create("ws://127.0.0.1:" + port + "/ws/netty");
            HttpClient client = HttpClient.newHttpClient();
            ExecutionException denied =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    client.newWebSocketBuilder()
                                            .buildAsync(uri, new Inbox())
                                            .get(5, TimeUnit.SECONDS));
            assertInstanceOf(WebSocketHandshakeException.class, denied.getCause());
            Inbox sender = new Inbox();
            Inbox receiver = new Inbox();
            first =
                    client.newWebSocketBuilder()
                            .header("Authorization", "Bearer " + token1)
                            .buildAsync(uri, sender)
                            .get(5, TimeUnit.SECONDS);
            second =
                    client.newWebSocketBuilder()
                            .header("Authorization", token2)
                            .buildAsync(uri, receiver)
                            .get(5, TimeUnit.SECONDS);
            first.sendText("ping", true).get(5, TimeUnit.SECONDS);
            second.sendText("ping", true).get(5, TimeUnit.SECONDS);
            assertEquals("pong", sender.take());
            assertEquals("pong", receiver.take());
            first.sendText(MessageFlowTest.MESSAGE, true).get(5, TimeUnit.SECONDS);
            String payload = pushed.poll(5, TimeUnit.SECONDS);
            assertNotNull(payload);
            new ConsumerMessageService().consume(payload);
            String echo = sender.take();
            assertEquals(echo, receiver.take());
            assertTrue(echo.contains("你好"));
            assertTrue(echo.contains("1001"));
        } finally {
            if (first != null) first.abort();
            if (second != null) second.abort();
            server.stop();
        }
        assertNull(ChannelManager.getChannelByUserId("1001"));
        assertNull(ChannelManager.getChannelByUserId("2002"));
        try (ServerSocket reused = new ServerSocket(port)) {
            assertEquals(port, reused.getLocalPort());
        }
    }

    private static class Inbox implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        String take() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "WebSocket 未在超时时间内返回文本帧");
            return message;
        }
    }
}
