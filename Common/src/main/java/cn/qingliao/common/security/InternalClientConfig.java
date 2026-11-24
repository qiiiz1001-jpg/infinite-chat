package cn.qingliao.common.security;

import feign.RequestInterceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InternalClientConfig {
    @Bean
    public RequestInterceptor internalAuthentication(
            @Value("${chat.internal-token:local-internal-change-me}") String token) {
        return template -> template.header("X-Internal-Token", token);
    }
}
