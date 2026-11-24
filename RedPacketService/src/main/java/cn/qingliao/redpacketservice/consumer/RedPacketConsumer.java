package cn.qingliao.redpacketservice.consumer;

import cn.hutool.json.JSONUtil;
import cn.qingliao.redpacketservice.config.KafkaConfig;
import cn.qingliao.redpacketservice.constant.KafkaConstant;
import cn.qingliao.redpacketservice.model.dto.RedPacketReceiveEvent;
import cn.qingliao.redpacketservice.service.RedPacketService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class RedPacketConsumer {
    @Autowired private RedPacketService redPacketService;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_REDPACKET_RECEIVE,
            groupId = KafkaConstant.GROUP_RECEIVE_HANDLER,
            concurrency = KafkaConstant.DEFAULT_CONCURRENCY)
    public void handleRedPacketReceive(String message) {
        log.info("收到红包领取事件: {}", message);
        // 1. 反序列化：报文格式错误属于脏消息，重试也不会成功，直接丢弃避免阻塞分区
        RedPacketReceiveEvent event;
        try {
            event = JSONUtil.toBean(message, RedPacketReceiveEvent.class);
        } catch (Exception e) {
            log.error("红包领取事件反序列化失败，丢弃该消息: {}", message, e);
            return;
        }
        // 2. 必填字段校验，防止生产者漏传字段导致后续 NPE
        if (event == null
                || event.getUserId() == null
                || event.getRedPacketId() == null
                || event.getReceivedAmount() == null) {
            log.error("红包领取事件字段缺失，丢弃该消息: {}", message);
            return;
        }
        Long userId = event.getUserId();
        Long redPacketId = event.getRedPacketId();
        Long receivedAmount = event.getReceivedAmount();
        // 领取时间非关键字段，缺失时用当前时间兜底
        Long receiveTime =
                event.getReceiveTime() != null
                        ? event.getReceiveTime()
                        : System.currentTimeMillis();
        // 3. 业务处理：这里的失败（如数据库抖动）才是可重试的
        try {
            redPacketService.handleRedPacketReceive(
                    userId, redPacketId, receivedAmount, receiveTime);
            log.info("红包领取事件处理成功，红包ID: {}, 用户ID: {}", redPacketId, userId);
        } catch (Exception e) {
            log.error("处理红包领取事件失败，红包ID: {}, 用户ID: {}", redPacketId, userId, e);
            throw new IllegalStateException("领取入账失败，需要重试", e);
        }
    }

    @KafkaListener(
            topics = KafkaConfig.TOPIC_REDPACKET_COMPLETED,
            groupId = KafkaConstant.GROUP_COMPLETED_HANDLER,
            concurrency = KafkaConstant.DEFAULT_CONCURRENCY)
    public void handleRedPacketCompleted(String message) {
        try {
            log.info("收到红包领完事件: {}", message);
            // 解析消息
            Map<String, Object> eventData = JSONUtil.toBean(message, Map.class);
            Long redPacketId = Long.parseLong(eventData.get("redPacketId").toString());
            // 调用服务层处理领完事件
            redPacketService.handleRedPacketCompleted(redPacketId);
            log.info("红包领完事件处理成功，红包ID: {}", redPacketId);
        } catch (Exception e) {
            log.error("处理红包领完事件失败: {}", message, e);
            throw new IllegalStateException("红包终态处理失败，需要重试", e);
        }
    }
}
