package cn.qingliao.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.utils.JwtUtil;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class AuthorizeFilterTest {
    @Test
    @SuppressWarnings("unchecked")
    void protectedRefreshUriDoesNotInheritRefreshWhitelist() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/refresh/uri"));
        new AuthorizeFilter(redis)
                .filter(
                        exchange,
                        e -> {
                            fail("不能放行");
                            return Mono.empty();
                        })
                .block();
        assertEquals(401, exchange.getResponse().getStatusCode().value());
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validAccessOverwritesSpoofedIdentity() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String token = JwtUtil.generate("1001", 5, TimeUnit.MINUTES);
        when(values.get(CommonConstant.ACCESS_TOKEN_PREFIX + "1001")).thenReturn(Mono.just(token));
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/user/info")
                                .header("Access-Token", token)
                                .header("X-User-Id", "9999"));
        AtomicReference<String> identity = new AtomicReference<>();
        new AuthorizeFilter(redis)
                .filter(
                        exchange,
                        e -> {
                            identity.set(e.getRequest().getHeaders().getFirst("X-User-Id"));
                            return Mono.empty();
                        })
                .block();
        assertEquals("1001", identity.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshOnlyRequestsRefreshInsteadOfAccess() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String token = JwtUtil.generate("1001", 7, TimeUnit.DAYS);
        when(values.get(CommonConstant.REFRESH_TOKEN_PREFIX + "1001")).thenReturn(Mono.just(token));
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/user/info").header("Refresh-Token", token));
        new AuthorizeFilter(redis)
                .filter(
                        exchange,
                        e -> {
                            fail("Refresh Token 不能访问业务");
                            return Mono.empty();
                        })
                .block();
        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("40103"));
    }
}
