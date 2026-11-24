package cn.qingliao.redpacketservice.scheduler;

import cn.hutool.json.JSONUtil;
import cn.qingliao.redpacketservice.config.KafkaConfig;
import cn.qingliao.redpacketservice.constant.RedisKeyConstant;
import cn.qingliao.redpacketservice.mapper.RedPacketMapper;
import cn.qingliao.redpacketservice.model.dto.RedPacketReceiveEvent;
import cn.qingliao.redpacketservice.model.entity.RedPacket;
import cn.qingliao.redpacketservice.service.impl.RedPacketServiceImpl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/** 修复进程崩溃、短时投递失败形成的窗口；依赖 Redis 持久化和七天记录保留。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "chat.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RedPacketRepairJob {
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final RedPacketMapper packets;
    private final RedPacketServiceImpl service;

    @Scheduled(fixedDelayString = "${redpacket.repair-delay-ms:30000}")
    @SchedulerLock(name = "redpacket-repair", lockAtMostFor = "PT2M")
    public void repair() {
        try {
            retryUnpublished();
            retryReceipts();
            for (RedPacket packet :
                    packets.selectList(
                            new LambdaQueryWrapper<RedPacket>()
                                    .eq(RedPacket::getStatus, 0)
                                    .le(
                                            RedPacket::getCreatedTime,
                                            new Date(System.currentTimeMillis() - 86400000L))
                                    .orderByAsc(RedPacket::getCreatedTime)
                                    .last("LIMIT 100"))) {
                service.handleRedPacketExpiration(packet.getRedPacketId());
            }
        } catch (RuntimeException error) {
            log.error("红包补偿暂未完成，将在下一轮重试", error);
        }
    }

    private void retryUnpublished() {
        try (Cursor<String> cursor =
                redis.scan(
                        ScanOptions.scanOptions().match("redpacket:*:grant").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (!"1".equals(redis.opsForHash().get(key, "published"))) {
                    service.publishPending(Long.valueOf(key.split(":")[1]));
                }
            }
        }
    }

    private void retryReceipts() {
        try (Cursor<String> cursor =
                redis.scan(
                        ScanOptions.scanOptions()
                                .match("redpacket:*:records")
                                .count(100)
                                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long packetId = Long.valueOf(key.split(":")[1]);
                String ackKey = "redpacket:" + packetId + ":credit-ack";
                for (Map.Entry<Object, Object> item : redis.opsForHash().entries(key).entrySet()) {
                    if (Boolean.TRUE.equals(redis.opsForHash().hasKey(ackKey, item.getKey())))
                        continue;
                    Object recordedTime = redis.opsForHash().get(key + ":times", item.getKey());
                    long time =
                            recordedTime == null
                                    ? System.currentTimeMillis()
                                    : Long.parseLong(recordedTime.toString());
                    RedPacketReceiveEvent event =
                            RedPacketReceiveEvent.builder()
                                    .userId(Long.valueOf(item.getKey().toString()))
                                    .redPacketId(packetId)
                                    .receivedAmount(Long.valueOf(item.getValue().toString()))
                                    .receiveTime(time)
                                    .build();
                    kafka.send(
                                    KafkaConfig.TOPIC_REDPACKET_RECEIVE,
                                    packetId.toString(),
                                    JSONUtil.toJsonStr(event))
                            .join();
                }
                // 最后一份已从池中取走但“领完事件”丢失时，也可恢复终态。
                if (!redis.opsForHash().entries(key).isEmpty()
                        && !Boolean.TRUE.equals(
                                redis.hasKey(RedisKeyConstant.getPoolKey(packetId)))) {
                    RedPacket packet = packets.selectById(packetId);
                    if (packet != null && packet.getStatus() == 0) {
                        if (packet.getCreatedTime().getTime() + 86400000L
                                <= System.currentTimeMillis())
                            service.handleRedPacketExpiration(packetId);
                        else service.handleRedPacketCompleted(packetId);
                    }
                }
            }
        }
    }
}
