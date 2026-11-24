package cn.qingliao.redpacketservice.config;

import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.redpacketservice.constant.*;
import cn.qingliao.redpacketservice.mapper.*;
import cn.qingliao.redpacketservice.model.dto.*;
import cn.qingliao.redpacketservice.model.entity.*;
import cn.qingliao.redpacketservice.model.vo.*;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.*;

import java.math.*;
import java.util.*;
import java.util.List;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class RedisConfig {
    @Bean
    public DefaultRedisScript<List> receiveRedPacketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(
                new org.springframework.core.io.ClassPathResource("lua/receive-redpacket.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<List> scanExpiredRedPacketsScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        String luaScript =
                """
local zsetKey   = KEYS[1]
local nowArg    = ARGV[1]
local maxCount  = tonumber(ARGV[2])

if not maxCount or maxCount <= 0 then
    maxCount = 500
end

-- 如未显式传入"当前时间"，则使用 Redis TIME 决定"现在"的毫秒时间戳
local nowMs
if not nowArg or nowArg == '' or tonumber(nowArg) <= 0 then
    local t = redis.call('TIME')
    nowMs = (tonumber(t[1]) * 1000) + math.floor(tonumber(t[2]) / 1000)
else
    nowMs = tonumber(nowArg)
end

-- 1) 取出最多 maxCount 条已到期成员
local expired = redis.call('ZRANGEBYSCORE', zsetKey, '-inf', nowMs, 'LIMIT', 0, maxCount)

if #expired == 0 then
    return {}
end

-- 2) 精确删除本次取到的这些成员
for i = 1, #expired do
    redis.call('ZREM', zsetKey, expired[i])
end

-- 3) 将这批已到期成员返回给调用方
return expired
""";
        script.setScriptText(luaScript);
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> calculateRemainAmountScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(
                new org.springframework.core.io.ClassPathResource("lua/freeze-refund.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
