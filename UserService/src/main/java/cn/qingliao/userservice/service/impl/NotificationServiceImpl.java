package cn.qingliao.userservice.service.impl;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.constant.MessageTypeConstant;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.common.utils.SnowflakeUtil;
import cn.qingliao.userservice.constant.*;
import cn.qingliao.userservice.constant.KafkaTopicConstant;
import cn.qingliao.userservice.mapper.*;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.FriendApplicationNotificationDTO;
import cn.qingliao.userservice.model.dto.NewSessionNotificationDTO;
import cn.qingliao.userservice.model.dto.SystemNotificationMessage;
import cn.qingliao.userservice.model.dto.request.*;
import cn.qingliao.userservice.model.entity.*;
import cn.qingliao.userservice.model.vo.*;
import cn.qingliao.userservice.service.*;
import cn.qingliao.userservice.service.NotificationService;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public NotificationServiceImpl(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void pushNewApply(Long userId, FriendApplicationNotificationDTO notification) {
        try {
            SystemNotificationMessage message = new SystemNotificationMessage();
            message.setMessageId(generateMessageId());
            message.setSessionId(null); // 好友申请不属于任何会话
            message.setSenderId(notification.getApplyUserId()); // 申请人ID
            message.setReceiverId(userId);
            message.setType(MessageTypeConstant.TYPE_SYSTEM_NEW_APPLY); // 101
            message.setSessionType(null); // 不适用于会话
            message.setTimestamp(System.currentTimeMillis());
            // 构建body
            Map<String, Object> body = new HashMap<>();
            body.put("nickname", notification.getApplyUserName());
            body.put("avatar", notification.getApplyFriendAvatar());
            body.put("msg", notification.getMessage());
            message.setBody(body);
            sendNotification(message, "好友申请通知");
        } catch (Exception e) {
            log.error("发送好友申请通知失败，用户ID: {}, 错误: {}", userId, e.getMessage(), e);
        }
    }

    private String generateMessageId() {
        return "msg_" + System.currentTimeMillis() + "_" + SnowflakeUtil.nextId();
    }

    private void sendNotification(SystemNotificationMessage message, String notificationName) {
        String messageJson = JSONUtil.toJsonStr(message);
        kafkaTemplate
                .send(
                        KafkaTopicConstant.TOPIC_SYSTEM_NOTIFICATION,
                        String.valueOf(message.getReceiverId()), // 使用 ReceiverId 作为key，保证同一用户的消息顺序
                        messageJson)
                .whenComplete(
                        (result, ex) -> {
                            if (ex == null) {
                                log.info(
                                        "{}发送成功，messageId: {}, 用户ID: {}, type: {}",
                                        notificationName,
                                        message.getMessageId(),
                                        message.getReceiverId(),
                                        message.getType());
                            } else {
                                log.error(
                                        "{}发送失败，messageId: {}, 用户ID: {}, 错误: {}",
                                        notificationName,
                                        message.getMessageId(),
                                        message.getReceiverId(),
                                        ex.getMessage());
                            }
                        });
    }

    @Override
    public void pushNewSession(
            Long senderId,
            Long userId,
            Long sessionId,
            Integer sessionType,
            NewSessionNotificationDTO notification) {
        try {
            SystemNotificationMessage message = new SystemNotificationMessage();
            message.setMessageId(generateMessageId());
            message.setSessionId(sessionId);
            message.setSenderId(senderId);
            message.setReceiverId(userId);
            message.setType(MessageTypeConstant.TYPE_SYSTEM_NEW_SESSION); // 102
            message.setSessionType(sessionType);
            message.setTimestamp(System.currentTimeMillis());
            // 构建body
            Map<String, Object> body = new HashMap<>();
            body.put("sessionName", notification.getSessionName());
            body.put("avatar", notification.getAvatar());
            message.setBody(body);
            sendNotification(message, "新会话通知");
        } catch (Exception e) {
            log.error("发送新会话通知失败，用户ID: {}, 会话ID: {}, 错误: {}", userId, sessionId, e.getMessage(), e);
        }
    }
}
