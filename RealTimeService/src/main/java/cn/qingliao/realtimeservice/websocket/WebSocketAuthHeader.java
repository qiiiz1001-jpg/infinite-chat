package cn.qingliao.realtimeservice.websocket;

import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.utils.JwtUtil;

import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@RequiredArgsConstructor
public class WebSocketAuthHeader extends ChannelInboundHandlerAdapter {
    private final StringRedisTemplate redis;

    /** HTTP 升级前同时校验 JWT 与 Redis 当前凭证；拒绝时必须释放引用计数消息。 */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            String token = request.headers().get(HttpHeaderNames.AUTHORIZATION);
            // 浏览器不能自定义握手 Authorization，凭证通过子协议传递并执行同一套校验。
            if (token == null) {
                String protocols = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
                if (protocols != null) {
                    for (String protocol : protocols.split(",")) {
                        String value = protocol.strip();
                        if (value.startsWith("bearer.")) token = value.substring(7);
                    }
                }
            }
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            Claims claims = JwtUtil.parse(token);
            String userId = claims == null ? null : claims.getSubject();
            if (userId == null || !userId.matches("[1-9][0-9]{0,18}")) {
                reject(ctx, msg, HttpResponseStatus.UNAUTHORIZED);
                return;
            }
            Long.parseLong(userId);
            String stored = redis.opsForValue().get(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
            if (!token.equals(stored)) {
                reject(ctx, msg, HttpResponseStatus.UNAUTHORIZED);
                return;
            }
            ChannelManager.authenticate(ctx.channel(), userId);
            ChannelManager.token(ctx.channel(), token);
            // 鉴权仅针对握手，后续文本帧由业务处理器处理。
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(msg);
        } catch (Exception e) {
            log.warn("WebSocket 鉴权失败，channel={}", ctx.channel().id());
            reject(ctx, msg, HttpResponseStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void reject(ChannelHandlerContext ctx, Object msg, HttpResponseStatus status) {
        ReferenceCountUtil.release(msg);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
