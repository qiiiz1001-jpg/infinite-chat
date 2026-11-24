package cn.qingliao.userservice.scheduler;

import cn.hutool.json.JSONUtil;
import cn.qingliao.userservice.constant.KafkaTopicConstant;
import cn.qingliao.userservice.model.dto.FriendRequestExpirationEvent;

import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "chat.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FriendRequestExpirationDispatcher {
    private static final String ZSET_KEY = "friend-request-expire-zset";
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_TICK = 5;
    private static final long TIME_BUDGET_MS = 400;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DefaultRedisScript<List> scanExpiredScript;

    public FriendRequestExpirationDispatcher(
            StringRedisTemplate redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            DefaultRedisScript<List> scanExpiredScript) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.scanExpiredScript = scanExpiredScript;
    }

    @Scheduled(fixedRate = 1000) // 每秒执行一次
    @SchedulerLock(
            name = "FriendRequestExpirationDispatcher",
            lockAtMostFor = "800ms", // 最多锁定800ms
            lockAtLeastFor = "200ms" // 至少锁定200ms
            )
    public void dispatch() {
        long start = System.currentTimeMillis();
        int batches = 0;
        try {
            while (batches < MAX_BATCHES_PER_TICK
                    && (System.currentTimeMillis() - start) < TIME_BUDGET_MS) {
                // 1. 执行Lua脚本，获取已到期的好友申请ID
                List<String> expiredIds =
                        redisTemplate.execute(
                                scanExpiredScript,
                                Collections.singletonList(ZSET_KEY),
                                "0", // 使用Redis TIME命令获取当前时间
                                String.valueOf(BATCH_SIZE));
                if (expiredIds == null || expiredIds.isEmpty()) {
                    // 没有更多到期任务，退出
                    break;
                }
                log.info("扫描到{}个已过期的好友申请", expiredIds.size());
                // 2. 逐条投递到Kafka
                for (String applyFriendIdStr : expiredIds) {
                    try {
                        Long applyFriendId = Long.parseLong(applyFriendIdStr);
                        FriendRequestExpirationEvent event = new FriendRequestExpirationEvent();
                        event.setApplyFriendId(applyFriendId);
                        event.setExpireTime(System.currentTimeMillis());
                        String eventJson = JSONUtil.toJsonStr(event);
                        kafkaTemplate.send(
                                KafkaTopicConstant.TOPIC_FRIEND_REQUEST_EXPIRATION,
                                String.valueOf(applyFriendId), // 使用applyFriendId作为key
                                eventJson);
                        log.debug("好友申请过期事件已投递，申请ID: {}", applyFriendId);
                    } catch (Exception e) {
                        log.error(
                                "投递好友申请过期事件失败，申请ID: {}, 错误: {}",
                                applyFriendIdStr,
                                e.getMessage(),
                                e);
                    }
                }
                batches++;
                // 3. 如果本批数据未满，说明已拉空，退出
                if (expiredIds.size() < BATCH_SIZE) {
                    break;
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            if (batches > 0) {
                log.info("好友申请过期任务调度完成，处理批次: {}, 耗时: {}ms", batches, elapsed);
            }
        } catch (Exception e) {
            log.error("好友申请过期任务调度失败: {}", e.getMessage(), e);
        }
    }
}
