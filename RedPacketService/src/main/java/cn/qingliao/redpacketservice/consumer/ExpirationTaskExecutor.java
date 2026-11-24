package cn.qingliao.redpacketservice.consumer;

import cn.qingliao.redpacketservice.config.KafkaConfig;
import cn.qingliao.redpacketservice.constant.KafkaConstant;
import cn.qingliao.redpacketservice.service.RedPacketService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ExpirationTaskExecutor {
    @Autowired private RedPacketService redPacketService;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_REDPACKET_EXPIRATION,
            groupId = KafkaConstant.GROUP_EXPIRATION_EXECUTOR,
            concurrency = KafkaConstant.DEFAULT_CONCURRENCY)
    public void handleRedPacketExpiration(String redPacketId) {
        try {
            log.info("收到红包过期事件，红包ID: {}", redPacketId);
            // 解析红包ID
            Long id = Long.parseLong(redPacketId);
            // 调用服务层处理过期逻辑
            redPacketService.handleRedPacketExpiration(id);
            log.info("红包过期事件处理成功，红包ID: {}", redPacketId);
        } catch (NumberFormatException e) {
            log.error("红包ID格式错误: {}", redPacketId, e);
        } catch (Exception e) {
            log.error("处理红包过期事件失败，红包ID: {}", redPacketId, e);
            // 注意：这里可以根据需要决定是否抛出异常来触发重试
            // 如果抛出异常，Kafka会根据配置进行重试
        }
    }
}
