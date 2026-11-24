package cn.qingliao.userservice.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class ShedLockConfig {
    private static final String LOCK_KEY_PREFIX = "UserService";

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, LOCK_KEY_PREFIX);
    }
}
