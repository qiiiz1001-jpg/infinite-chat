package cn.qingliao.realtimeservice.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.utils.JwtUtil;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

class WebSocketAuthTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void validBearerTokenPassesAndSetsTrustedIdentity() {
        String token = JwtUtil.generate("1001", 5, TimeUnit.MINUTES);
        when(values.get(CommonConstant.ACCESS_TOKEN_PREFIX + "1001")).thenReturn(token);
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketAuthHeader(redis));
        FullHttpRequest request = request("Bearer " + token);
        try {
            assertTrue(channel.writeInbound(request));
            assertSame(request, channel.readInbound());
            assertEquals("1001", ChannelManager.getUserIdByChannel(channel));
            // HTTP 还没升级，不应提前暴露给推送消费者。
            assertNull(ChannelManager.getChannelByUserId("1001"));
        } finally {
            request.release();
            ChannelManager.unbind(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void browserSubprotocolUsesSameJwtAndRedisValidation() {
        String token = JwtUtil.generate("1001", 5, TimeUnit.MINUTES);
        when(values.get(CommonConstant.ACCESS_TOKEN_PREFIX + "1001")).thenReturn(token);
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketAuthHeader(redis));
        FullHttpRequest request = request(null);
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "im, bearer." + token);
        try {
            assertTrue(channel.writeInbound(request));
            assertSame(request, channel.readInbound());
            assertEquals("1001", ChannelManager.getUserIdByChannel(channel));
        } finally {
            request.release();
            ChannelManager.unbind(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void missingOrExpiredTokenIsRejectedAndReleased() {
        assertRejected(null, HttpResponseStatus.UNAUTHORIZED);
        assertRejected(
                JwtUtil.generate("1001", -5, TimeUnit.MINUTES), HttpResponseStatus.UNAUTHORIZED);
        verifyNoInteractions(redis);
    }

    @Test
    void replacedOrLoggedOutTokenIsRejected() {
        String token = JwtUtil.generate("1001", 5, TimeUnit.MINUTES);
        when(values.get(anyString())).thenReturn("another-token");
        assertRejected(token, HttpResponseStatus.UNAUTHORIZED);
        when(values.get(anyString())).thenReturn(null);
        assertRejected(token, HttpResponseStatus.UNAUTHORIZED);
    }

    @Test
    void redisFailureDoesNotPassHandshake() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        assertRejected(
                JwtUtil.generate("1001", 5, TimeUnit.MINUTES),
                HttpResponseStatus.SERVICE_UNAVAILABLE);
    }

    private void assertRejected(String token, HttpResponseStatus status) {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketAuthHeader(redis));
        FullHttpRequest request = request(token);
        try {
            assertFalse(channel.writeInbound(request));
            FullHttpResponse response = channel.readOutbound();
            try {
                assertEquals(status, response.status());
                assertEquals(0, request.refCnt());
                assertFalse(channel.isActive());
            } finally {
                response.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private FullHttpRequest request(String token) {
        FullHttpRequest request =
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws/netty");
        if (token != null) request.headers().set(HttpHeaderNames.AUTHORIZATION, token);
        return request;
    }
}
