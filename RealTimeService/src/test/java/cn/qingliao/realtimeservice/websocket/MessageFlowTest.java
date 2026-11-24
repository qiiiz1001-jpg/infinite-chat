package cn.qingliao.realtimeservice.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.KnowledgeSession;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.realtimeservice.consumer.ConsumerMessageService;
import cn.qingliao.realtimeservice.utils.SnowflakeDynamicUtil;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

class MessageFlowTest {
    static final String MESSAGE =
            "{\"sessionId\":99,\"receiverId\":2002,\"senderId\":9999,\"type\":0,\"sessionType\":0,\"content\":\"你好\",\"clientMessageId\":\"client-1\",\"messageId\":1}";
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private cn.qingliao.realtimeservice.client.UserServiceClient users() {
        var users = mock(cn.qingliao.realtimeservice.client.UserServiceClient.class);
        when(users.canSend(anyLong(), anyLong(), anyInt(), nullable(Long.class))).thenReturn(true);
        return users;
    }

    private ConnectionStateService states() {
        var state = mock(ConnectionStateService.class);
        when(state.isCurrent(any(), any())).thenReturn(true);
        return state;
    }

    private EmbeddedChannel channel(String userId) {
        return channel(userId, users());
    }

    private EmbeddedChannel channel(
            String userId, cn.qingliao.realtimeservice.client.UserServiceClient users) {
        EmbeddedChannel channel =
                new EmbeddedChannel(
                        DefaultChannelId.newInstance(),
                        new WebSocketHandler(
                                kafka, new SnowflakeDynamicUtil(1, 1), users, states()));
        ChannelManager.bind(userId, channel);
        return channel;
    }

    @Test
    void overwritesSpoofedIdentityAndPublishesSameMessageToBothTopics() {
        EmbeddedChannel sender = channel("1001");
        try {
            sender.writeInbound(new TextWebSocketFrame(MESSAGE));
            ArgumentCaptor<String> store = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> push = ArgumentCaptor.forClass(String.class);
            verify(kafka)
                    .send(eq(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE), eq("99"), store.capture());
            verify(kafka)
                    .send(eq(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH), eq("99"), push.capture());
            assertEquals(store.getValue(), push.getValue());
            MessageRequest request = JSONUtil.toBean(store.getValue(), MessageRequest.class);
            assertEquals(1001L, request.getSenderId());
            assertNotEquals(1L, request.getMessageId());
            assertNotNull(request.getCreatedTime());
            assertEquals("你好", request.getContent());
        } finally {
            sender.finishAndReleaseAll();
        }
    }

    @Test
    void heartbeatAndInvalidMessagesDoNotEnterKafka() {
        EmbeddedChannel sender = channel("1001");
        try {
            sender.writeInbound(new TextWebSocketFrame("ping"));
            assertEquals("pong", takeText(sender));
            sender.writeInbound(new TextWebSocketFrame("invalid json"));
            assertTrue(takeText(sender).contains("INVALID_MESSAGE"));
            sender.writeInbound(
                    new TextWebSocketFrame(
                            MESSAGE.replace("\"sessionType\":0", "\"sessionType\":1")));
            assertTrue(takeText(sender).contains("INVALID_MESSAGE"));
            verifyNoInteractions(kafka);
        } finally {
            sender.finishAndReleaseAll();
        }
    }

    @Test
    void assistantReferenceRequiresMembershipAndReachesAiTopic() {
        var users = users();
        when(users.knowledgeAccess(1001L, 99L)).thenReturn(new KnowledgeSession(true, true));
        when(users.knowledgeAccess(1001L, 18L))
                .thenReturn(new KnowledgeSession(false, false), new KnowledgeSession(true, false));
        var sender = channel("1001", users);
        String payload =
                MESSAGE.replace("\"sessionType\":0", "\"sessionType\":2,\"referenceSessionId\":18");
        try {
            sender.writeInbound(new TextWebSocketFrame(payload));
            assertTrue(takeText(sender).contains("REFERENCE_ACCESS_DENIED"));
            verifyNoInteractions(kafka);
            sender.writeInbound(new TextWebSocketFrame(payload));
            var record = ArgumentCaptor.forClass(String.class);
            verify(kafka).send(eq("ai-request-topic"), eq("99"), record.capture());
            assertEquals(
                    18L,
                    JSONUtil.toBean(record.getValue(), MessageRequest.class)
                            .getReferenceSessionId());
        } finally {
            sender.finishAndReleaseAll();
        }
    }

    @Test
    void partialPublishFailureIsReportedAndOtherTopicIsStillAttempted() {
        when(kafka.send(eq(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE), anyString(), anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("broker unavailable")));
        EmbeddedChannel sender = channel("1001");
        try {
            sender.writeInbound(new TextWebSocketFrame(MESSAGE));
            assertTrue(takeText(sender).contains("MESSAGE_DISPATCH_FAILED"));
            verify(kafka).send(eq(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH), eq("99"), anyString());
        } finally {
            sender.finishAndReleaseAll();
        }
    }

    @Test
    void lateOldConnectionCleanupKeepsNewConnectionAndIdleRemovesIt() {
        EmbeddedChannel old = channel("1001");
        EmbeddedChannel current = channel("1001");
        try {
            assertFalse(old.isActive());
            ChannelManager.unbind(old);
            assertSame(current, ChannelManager.getChannelByUserId("1001"));
            current.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
            assertFalse(current.isActive());
            assertNull(ChannelManager.getChannelByUserId("1001"));
        } finally {
            old.finishAndReleaseAll();
            current.finishAndReleaseAll();
        }
    }

    @Test
    void pushConsumerEchoesContentToSenderAndReceiverAndSkipsOfflineUser() {
        EmbeddedChannel sender = channel("1001");
        EmbeddedChannel receiver = channel("2002");
        try {
            sender.writeInbound(new TextWebSocketFrame(MESSAGE));
            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(kafka)
                    .send(eq(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH), eq("99"), payload.capture());
            ConsumerMessageService consumer = new ConsumerMessageService();
            consumer.consume(payload.getValue());
            String echo = takeText(sender);
            assertEquals(echo, takeText(receiver));
            assertEquals("你好", JSONUtil.parseObj(echo).getStr("content"));
            receiver.close();
            assertDoesNotThrow(() -> consumer.consume(payload.getValue()));
            assertNotNull(takeText(sender));
        } finally {
            sender.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
        }
    }

    private String takeText(EmbeddedChannel channel) {
        TextWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame);
        try {
            return frame.text();
        } finally {
            frame.release();
        }
    }
}
