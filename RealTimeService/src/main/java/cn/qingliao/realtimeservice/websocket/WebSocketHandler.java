package cn.qingliao.realtimeservice.websocket;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.MessageBody;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.realtimeservice.client.UserServiceClient;
import cn.qingliao.realtimeservice.constant.WebSocketConstant;
import cn.qingliao.realtimeservice.utils.SnowflakeDynamicUtil;

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final KafkaTemplate<String, String> kafka;
    private final SnowflakeDynamicUtil ids;
    private final UserServiceClient users;
    private final ConnectionStateService connections;

    /** 心跳直接回复；业务消息补齐可信字段后分别投递存储与推送 Topic。 */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        if (!connections.isCurrent(
                ChannelManager.getUserIdByChannel(ctx.channel()),
                ChannelManager.token(ctx.channel()))) {
            ctx.close();
            return;
        }
        if (WebSocketConstant.HEARTBEAT_PING.equals(frame.text())) {
            ctx.writeAndFlush(new TextWebSocketFrame(WebSocketConstant.HEARTBEAT_PONG));
            return;
        }
        MessageRequest request;
        try {
            request = JSONUtil.toBean(frame.text(), MessageRequest.class);
            validate(request);
        } catch (RuntimeException e) {
            replyError(ctx.channel(), "INVALID_MESSAGE", "");
            return;
        }
        String userId = ChannelManager.getUserIdByChannel(ctx.channel());
        if (userId == null) {
            ctx.close();
            return;
        }
        // 不能信任客户端的 senderId，也不能让客户端决定服务端消息 ID 和时间。
        request.setSenderId(Long.valueOf(userId));
        if (!users.canSend(
                request.getSenderId(),
                request.getSessionId(),
                request.getSessionType(),
                request.getReceiverId())) {
            replyError(ctx.channel(), "SESSION_ACCESS_DENIED", request.getClientMessageId());
            return;
        }
        if (request.getReferenceSessionId() != null && !canReadReference(request)) {
            replyError(ctx.channel(), "REFERENCE_ACCESS_DENIED", request.getClientMessageId());
            return;
        }
        request.setMessageId(ids.nextId());
        request.setCreatedTime(new Date());
        String payload = JSONUtil.toJsonStr(request);
        String key = request.getSessionId().toString();
        CompletableFuture<SendResult<String, String>> store =
                send(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, key, payload);
        CompletableFuture<SendResult<String, String>> push =
                send(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH, key, payload);
        // 两次发送并非一个事务；失败提示不意味着另一个 Topic 没有成功，后续需补偿和幂等。
        CompletableFuture<SendResult<String, String>> ai =
                request.getSessionType() == 2
                        ? send("ai-request-topic", key, payload)
                        : CompletableFuture.completedFuture(null);
        CompletableFuture.allOf(store, push, ai)
                .whenComplete(
                        (result, error) -> {
                            if (error != null) {
                                log.warn("Kafka 投递失败，messageId={}", request.getMessageId(), error);
                                replyError(
                                        ctx.channel(),
                                        "MESSAGE_DISPATCH_FAILED",
                                        request.getClientMessageId());
                            }
                        });
    }

    private CompletableFuture<SendResult<String, String>> send(
            String topic, String key, String payload) {
        try {
            return kafka.send(topic, key, payload);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private boolean canReadReference(MessageRequest request) {
        try {
            var origin = users.knowledgeAccess(request.getSenderId(), request.getSessionId());
            if (origin == null || !origin.assistant()) return false;
            var reference =
                    users.knowledgeAccess(request.getSenderId(), request.getReferenceSessionId());
            return reference != null && reference.accessible();
        } catch (feign.FeignException unavailable) {
            return false;
        }
    }

    private void validate(MessageRequest request) {
        if (request != null && request.getBody() != null) {
            request.setContent(request.getBody().getContent());
            request.setReplyId(request.getBody().getReplyId());
        }
        if (request == null
                || request.getSessionId() == null
                || request.getSessionId() <= 0
                || request.getSessionType() == null
                || request.getSessionType() < 0
                || request.getSessionType() > 2
                || (request.getReferenceSessionId() != null
                        && (request.getSessionType() != 2 || request.getReferenceSessionId() <= 0))
                || (request.getSessionType() != 1
                        && (request.getReceiverId() == null || request.getReceiverId() <= 0))
                || (request.getSessionType() == 1 && request.getReceiverId() != null)
                || request.getType() == null
                || !java.util.Set.of(0, 1, 2, 4).contains(request.getType())
                || request.getContent() == null
                || request.getContent().isBlank()
                || request.getContent().length() > 4096
                || request.getClientMessageId() == null
                || request.getClientMessageId().isBlank()
                || request.getClientMessageId().length() > 128) {
            throw new IllegalArgumentException("消息结构不完整或类型不受支持");
        }
        MessageBody body = new MessageBody();
        body.setContent(request.getContent());
        body.setReplyId(request.getReplyId());
        request.setBody(body);
    }

    private void replyError(Channel channel, String code, String clientMessageId) {
        if (channel.isActive()) {
            channel.writeAndFlush(
                    new TextWebSocketFrame(
                            JSONUtil.toJsonStr(
                                    Map.of("error", code, "clientMessageId", clientMessageId))));
        }
    }

    /** 握手完成再加入在线索引；读超时释放连接与映射。 */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            String userId = ChannelManager.getUserIdByChannel(ctx.channel());
            if (userId != null) {
                ChannelManager.bind(
                        userId,
                        ctx.channel(),
                        () -> {
                            long disconnectedAt = System.currentTimeMillis();
                            ctx.executor()
                                    .execute(
                                            () -> connections.disconnected(userId, disconnectedAt));
                        });
            }
        } else if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            ctx.close();
        } else {
            super.userEventTriggered(ctx, event);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ChannelManager.unbind(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("WebSocket 连接异常，channel={}", ctx.channel().id(), cause);
        ctx.close();
    }
}
