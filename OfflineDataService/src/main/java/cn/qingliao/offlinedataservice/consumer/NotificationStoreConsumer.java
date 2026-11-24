package cn.qingliao.offlinedataservice.consumer;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.qingliao.common.constant.CommonConstant;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/** 系统通知 ID 为字符串，单独存储，不能送入要求 Long messageId 的聊天存储消费者。 */
@Component
@RequiredArgsConstructor
public class NotificationStoreConsumer {
    private final JdbcTemplate jdbc;

    @KafkaListener(
            topics = CommonConstant.KAFKA_SYSTEM_NOTIFICATION_TOPIC,
            groupId = "infinite-chat-notification-store")
    public void consume(String payload) {
        JSONObject notification = JSONUtil.parseObj(payload);
        String id = notification.getStr("messageId");
        Long receiver = notification.getLong("receiverId");
        if (id == null || receiver == null)
            throw new IllegalArgumentException("通知缺少 messageId/receiverId");
        jdbc.update(
                "INSERT INTO system_notification(message_id,receiver_id,type,payload,created_time)"
                        + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE message_id=message_id",
                id,
                receiver,
                notification.getInt("type"),
                payload,
                new Timestamp(notification.getLong("timestamp", System.currentTimeMillis())));
    }
}
