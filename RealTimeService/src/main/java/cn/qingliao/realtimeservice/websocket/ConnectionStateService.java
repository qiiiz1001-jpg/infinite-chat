package cn.qingliao.realtimeservice.websocket;

import cn.qingliao.common.constant.CommonConstant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectionStateService {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> SAVE_LATEST =
            new DefaultRedisScript<>(
                    "local old=redis.call('GET',KEYS[1]);if not old or"
                            + " tonumber(old)<tonumber(ARGV[1]) then"
                            + " redis.call('SET',KEYS[1],ARGV[1]);end;return 1",
                    Long.class);

    /** 退出或凭证被替换后，在下一次心跳/业务帧拒绝继续使用旧连接。 */
    public boolean isCurrent(String userId, String token) {
        return userId != null
                && token != null
                && token.equals(
                        redis.opsForValue().get(CommonConstant.ACCESS_TOKEN_PREFIX + userId));
    }

    public void disconnected(String userId, long timestamp) {
        try {
            redis.execute(
                    SAVE_LATEST,
                    List.of(CommonConstant.OFFLINE_KEY_REDIS + userId),
                    Long.toString(timestamp));
        } catch (RuntimeException error) {
            log.warn("离线时间保存失败，userId={}", userId, error);
        }
    }
}
