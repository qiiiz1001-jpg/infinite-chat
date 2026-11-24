package cn.qingliao.gateway.filter;

import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.utils.JwtUtil;

import io.jsonwebtoken.Claims;

import lombok.RequiredArgsConstructor;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 网关只核对凭证，业务权限仍由下游服务校验；Redis 使用响应式客户端。 */
@Component
@RequiredArgsConstructor
public class AuthorizeFilter implements GlobalFilter, Ordered {
    private final ReactiveStringRedisTemplate redis;
    private static final Set<String> PUBLIC_PATHS =
            Set.of(
                    "/api/user/login/code",
                    "/api/user/register",
                    "/api/user/sendCaptcha",
                    "/api/user/login/password",
                    "/api/user/refresh");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 不接受外部伪造的下游身份头；公开路径用完整匹配，不能把 refresh/uri 一起放行。
        ServerWebExchange clean =
                exchange.mutate()
                        .request(
                                exchange.getRequest()
                                        .mutate()
                                        .headers(
                                                headers -> {
                                                    headers.remove("X-User-Id");
                                                    headers.remove("X-Internal-Token");
                                                })
                                        .build())
                        .build();
        String path = clean.getRequest().getPath().value();
        // 禁止访问微服务之间内部访问的接口
        if (path.contains("/internal/"))
            return error(clean, ErrorCode.NO_AUTH_ERROR, HttpStatus.FORBIDDEN);
        // 公共接口直接放行
        if (PUBLIC_PATHS.contains(path)) return chain.filter(clean);
        String access = clean.getRequest().getHeaders().getFirst("Access-Token");
        String refresh = clean.getRequest().getHeaders().getFirst("Refresh-Token");
        return authenticate(access, CommonConstant.ACCESS_TOKEN_PREFIX)
                .map(userId -> new Authentication(userId, null))
                .switchIfEmpty(
                        authenticate(refresh, CommonConstant.REFRESH_TOKEN_PREFIX)
                                .map(userId -> new Authentication(null, ErrorCode.TOKEN_EXPIRED)))
                .defaultIfEmpty(new Authentication(null, ErrorCode.NOT_LOGIN_ERROR))
                .onErrorReturn(new Authentication(null, ErrorCode.SYSTEM_ERROR))
                .flatMap(
                        auth ->
                                auth.userId() == null
                                        ? error(
                                                clean,
                                                auth.error(),
                                                auth.error() == ErrorCode.SYSTEM_ERROR
                                                        ? HttpStatus.SERVICE_UNAVAILABLE
                                                        : HttpStatus.UNAUTHORIZED)
                                        : chain.filter(
                                                clean.mutate()
                                                        .request(
                                                                clean.getRequest()
                                                                        .mutate()
                                                                        .header(
                                                                                "X-User-Id",
                                                                                auth.userId())
                                                                        .build())
                                                        .build()));
    }

    private Mono<String> authenticate(String token, String prefix) {
        Claims claims = JwtUtil.parse(token);
        if (claims == null || claims.getSubject() == null) return Mono.empty();
        String userId = claims.getSubject();
        return redis.opsForValue()
                .get(prefix + userId)
                .filter(stored -> stored.equals(token))
                .map(stored -> userId);
    }

    private Mono<Void> error(ServerWebExchange exchange, ErrorCode code, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"code\":" + code.getCode() + ",\"message\":\"" + code.getMessage() + "\"}";
        return exchange.getResponse()
                .writeWith(
                        Mono.just(
                                exchange.getResponse()
                                        .bufferFactory()
                                        .wrap(json.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -100;
    }

    // 网关内部鉴权结果。
    private record Authentication(String userId, ErrorCode error) {}
}
