package cn.qingliao.common.security;

import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.utils.JwtUtil;

import io.jsonwebtoken.Claims;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthenticationConfig implements WebMvcConfigurer {
    private final StringRedisTemplate redis;

    @Value("${chat.internal-token:local-internal-change-me}")
    private String internalToken;

    private static final Set<String> PUBLIC =
            Set.of(
                    "/api/user/login/code",
                    "/api/user/login/password",
                    "/api/user/register",
                    "/api/user/sendCaptcha",
                    "/api/user/refresh");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                if ("OPTIONS".equals(request.getMethod())
                                        || PUBLIC.contains(request.getRequestURI())) return true;
                                String provided = request.getHeader("X-Internal-Token");
                                if (provided != null
                                        && MessageDigest.isEqual(
                                                provided.getBytes(StandardCharsets.UTF_8),
                                                internalToken.getBytes(StandardCharsets.UTF_8))) {
                                    request.setAttribute(CurrentUser.INTERNAL, true);
                                    return true;
                                }
                                if (request.getRequestURI().contains("/internal/"))
                                    throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
                                String token = request.getHeader("Access-Token");
                                Claims claims = JwtUtil.parse(token);
                                if (claims == null || claims.getSubject() == null)
                                    throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
                                String userId = claims.getSubject();
                                if (!token.equals(
                                        redis.opsForValue()
                                                .get(CommonConstant.ACCESS_TOKEN_PREFIX + userId)))
                                    throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
                                request.setAttribute(CurrentUser.USER_ID, userId);
                                return true;
                            }
                        })
                .addPathPatterns("/api/**");
    }
}
