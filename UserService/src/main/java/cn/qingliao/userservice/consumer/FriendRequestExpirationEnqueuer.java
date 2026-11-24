package cn.qingliao.userservice.consumer;

import static cn.qingliao.userservice.constant.KafkaTopicConstant.GROUP_FRIEND_REQUEST_ENQUEUER;
import static cn.qingliao.userservice.constant.KafkaTopicConstant.TOPIC_FRIEND_REQUEST_CREATION;

import cn.hutool.json.JSONUtil;
import cn.qingliao.userservice.model.dto.FriendRequestCreationEvent;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FriendRequestExpirationEnqueuer {
    private static final String ZSET_KEY = "friend-request-expire-zset";
    private final StringRedisTemplate redisTemplate;

    public FriendRequestExpirationEnqueuer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @KafkaListener(
            topics = TOPIC_FRIEND_REQUEST_CREATION,
            groupId = GROUP_FRIEND_REQUEST_ENQUEUER,
            concurrency = "1" // 单线程消费，保证顺序
            )
    public void enqueueExpirationTask(String message) {
        try {
            log.info("收到好友申请创建事件: {}", message);
            // 1. 解析事件
            FriendRequestCreationEvent event =
                    JSONUtil.toBean(message, FriendRequestCreationEvent.class);
            Long applyFriendId = event.getApplyFriendId();
            Long expireTime = event.getExpireTime();
            if (applyFriendId == null || expireTime == null) {
                log.error(
                        "好友申请事件数据不完整，applyFriendId: {}, expireTime: {}", applyFriendId, expireTime);
                return;
            }
            if (expireTime <= System.currentTimeMillis()) {
                log.warn("好友申请已过期，跳过注册，申请ID: {}, 过期时间: {}", applyFriendId, expireTime);
                return;
            }
            // 2. 注册到 Redis ZSET
            // ZADD friend-request-expire-zset <expireTime> <applyFriendId>
            Boolean result =
                    redisTemplate
                            .opsForZSet()
                            .add(ZSET_KEY, String.valueOf(applyFriendId), expireTime.doubleValue());
            if (Boolean.TRUE.equals(result)) {
                log.info("好友申请过期任务注册成功，申请ID: {}, 过期时间: {}", applyFriendId, expireTime);
            } else {
                log.info("好友申请过期任务注册失败，申请ID: {}, 过期时间: {}", applyFriendId, expireTime);
            }
        } catch (Exception e) {
            log.error("处理好友申请创建事件失败: {}, 错误: {}", message, e.getMessage(), e);
        }
    }
}
