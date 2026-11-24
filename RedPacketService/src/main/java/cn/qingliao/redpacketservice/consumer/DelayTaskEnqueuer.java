package cn.qingliao.redpacketservice.consumer;

import cn.hutool.json.JSONUtil;
import cn.qingliao.redpacketservice.config.KafkaConfig;
import cn.qingliao.redpacketservice.constant.KafkaConstant;
import cn.qingliao.redpacketservice.constant.RedPacketConstant;
import cn.qingliao.redpacketservice.constant.RedisKeyConstant;
import cn.qingliao.redpacketservice.model.dto.RedPacketCreationEvent;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DelayTaskEnqueuer {
    @Autowired private StringRedisTemplate stringRedisTemplate;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_REDPACKET_CREATION,
            groupId = KafkaConstant.GROUP_DELAY_TASK_ENQUEUER,
            concurrency = KafkaConstant.DEFAULT_CONCURRENCY)
    public void handleRedPacketCreation(String message) {
        try {
            log.info("收到红包创建事件: {}", message);
            // 解析消息
            RedPacketCreationEvent event = JSONUtil.toBean(message, RedPacketCreationEvent.class);
            Long redPacketId = event.getRedPacketId();
            Long createTime = event.getCreateTime();
            // 计算过期时间戳
            long expireTimestamp = createTime + RedPacketConstant.EXPIRE_TIME_MS;
            // 注册到Redis ZSET
            stringRedisTemplate
                    .opsForZSet()
                    .add(
                            RedisKeyConstant.EXPIRE_ZSET,
                            String.valueOf(redPacketId),
                            expireTimestamp);
            log.info(
                    "红包过期任务注册成功，红包ID: {}, 创建时间: {}, 过期时间: {}",
                    redPacketId,
                    createTime,
                    expireTimestamp);
        } catch (Exception e) {
            log.error("处理红包创建事件失败: {}", message, e);
            // 注意：如果处理失败，可以抛出异常触发Kafka重试
            // 这里捕获异常只是为了防止整个消费者组崩溃
            // 实际生产环境建议配合死信队列使用
        }
    }
}
