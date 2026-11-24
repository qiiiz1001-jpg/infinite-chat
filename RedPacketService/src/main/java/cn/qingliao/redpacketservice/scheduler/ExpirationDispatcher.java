package cn.qingliao.redpacketservice.scheduler;

import cn.qingliao.redpacketservice.config.KafkaConfig;
import cn.qingliao.redpacketservice.constant.RedisKeyConstant;

import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "chat.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ExpirationDispatcher {
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private DefaultRedisScript<List> scanExpiredRedPacketsScript;

    @Value("${redpacket.expire.batchSize:500}")
    private int batchSize;

    @Value("${redpacket.expire.maxBatchesPerTick:5}")
    private int maxBatchesPerTick;

    @Value("${redpacket.expire.timeBudgetMs:400}")
    private long timeBudgetMs;

    @Scheduled(fixedRate = 1000)
    @SchedulerLock(
            name = "dispatchExpiredRedPackets",
            lockAtMostFor = "800ms",
            lockAtLeastFor = "200ms")
    public void dispatch() {
        final long startTime = System.currentTimeMillis();
        int totalExpiredCount = 0;
        int batches = 0;
        try {
            // 多批次处理，避免单次扫描时间过长
            while (batches < maxBatchesPerTick
                    && (System.currentTimeMillis() - startTime) < timeBudgetMs) {
                // 执行Lua脚本：扫描并删除已到期的红包ID
                // KEYS[1]: redpacket-expire-zset
                // ARGV[1]: 0（让脚本使用Redis TIME命令获取当前时间）
                // ARGV[2]: batchSize（本批次最多获取的数量）
                List<String> expiredIds =
                        stringRedisTemplate.execute(
                                scanExpiredRedPacketsScript,
                                Collections.singletonList(RedisKeyConstant.EXPIRE_ZSET),
                                "0",
                                String.valueOf(batchSize));
                // 如果没有到期的红包，退出循环
                if (expiredIds == null || expiredIds.isEmpty()) {
                    break;
                }
                // 将每个到期的红包ID投递到Kafka
                for (String redPacketId : expiredIds) {
                    try {
                        kafkaTemplate.send(
                                KafkaConfig.TOPIC_REDPACKET_EXPIRATION, redPacketId, redPacketId);
                    } catch (Exception e) {
                        log.error("发送红包过期事件到Kafka失败，红包ID: {}", redPacketId, e);
                    }
                }
                totalExpiredCount += expiredIds.size();
                batches++;
                log.info("第{}批扫描完成，本批到期红包数: {}", batches, expiredIds.size());
                // 如果本批次返回的数量小于batchSize，说明已经扫描完所有到期任务
                if (expiredIds.size() < batchSize) {
                    break;
                }
            }
            if (totalExpiredCount > 0) {
                long costTime = System.currentTimeMillis() - startTime;
                log.info(
                        "过期红包扫描完成，总共处理: {} 个，耗时: {} ms，批次数: {}",
                        totalExpiredCount,
                        costTime,
                        batches);
            }
        } catch (Exception e) {
            log.error("扫描过期红包任务执行失败", e);
        }
    }
}
