package cn.qingliao.userservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

@Configuration
public class RedisConfig {
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public DefaultRedisScript<List> scanExpiredFriendRequestsScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        String luaScript =
                "local zsetKey = KEYS[1] local nowArg  = ARGV[1] local maxCount = tonumber(ARGV[2])"
                    + " if not maxCount or maxCount <= 0 then maxCount = 500 end local nowMs if"
                    + " (not nowArg) or nowArg == '' or tonumber(nowArg) <= 0 then   local t ="
                    + " redis.call('TIME');   nowMs = (tonumber(t[1]) * 1000) +"
                    + " math.floor(tonumber(t[2]) / 1000) else nowMs = tonumber(nowArg) end local"
                    + " expired = redis.call('ZRANGEBYSCORE', zsetKey, '-inf', nowMs, 'LIMIT', 0,"
                    + " maxCount) if #expired == 0 then return {} end for i=1,#expired do"
                    + " redis.call('ZREM', zsetKey, expired[i]) end return expired;";
        script.setScriptText(luaScript);
        script.setResultType(List.class);
        return script;
    }
}
