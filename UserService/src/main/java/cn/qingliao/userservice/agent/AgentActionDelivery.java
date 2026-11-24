package cn.qingliao.userservice.agent;

import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.AgentActions.Action;
import cn.qingliao.common.model.dto.MessageBody;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.userservice.service.SessionService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AgentActionDelivery {
    private final AgentActionRepository repo;
    private final AgentActionService actions;
    private final SessionService sessions;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String sender;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentActionDelivery(
            AgentActionRepository repo,
            AgentActionService actions,
            SessionService sessions,
            KafkaTemplate<String, String> kafka) {
        this(repo, actions, sessions, kafka, Clock.systemUTC());
    }

    AgentActionDelivery(
            AgentActionRepository repo,
            AgentActionService actions,
            SessionService sessions,
            KafkaTemplate<String, String> kafka,
            Clock clock) {
        this.repo = repo;
        this.actions = actions;
        this.sessions = sessions;
        this.kafka = kafka;
        this.clock = clock;
    }

    /** 条件领取抵御重复事件；两个 Topic 的确认分别保存，重试保留消息编号。 */
    public void execute(long id, int version) {
        long now = clock.millis();
        String token = UUID.randomUUID().toString();
        int claimed =
                repo.jdbc()
                        .update(
                                "UPDATE agent_action SET"
                                    + " status='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,message_time=COALESCE(message_time,?),updated_at=?"
                                    + " WHERE action_id=? AND version=? AND status='PENDING' AND"
                                    + " next_attempt_at<=?",
                                token,
                                now + 120000,
                                now,
                                now,
                                id,
                                version,
                                now);
        if (claimed == 0) return;
        Action a = repo.find(id);
        try {
            actions.authorize(a);
            if ("EMAIL".equals(a.getKind())) {
                sendEmail(a);
                markSent(id, token);
                return;
            }
            MessageRequest message = new MessageRequest();
            boolean reminder = "REMINDER".equals(a.getKind());
            var session = sessions.getById(a.getSessionId());
            message.setMessageId(a.getMessageId());
            message.setSessionId(a.getSessionId());
            message.setSenderId(reminder ? CommonConstant.AI_ID : a.getOwnerId());
            message.setReceiverId(reminder ? a.getOwnerId() : actions.receiver(a));
            message.setSessionType(session.getType());
            message.setType(0);
            message.setCreatedTime(new Date(a.getMessageTime()));
            String content = (reminder ? "[助手提醒] " : "[助手代发] ") + a.getContent();
            message.setContent(content);
            MessageBody body = new MessageBody();
            body.setContent(content);
            message.setBody(body);
            message.setClientMessageId("agent:" + a.getActionId());
            String payload = cn.qingliao.common.utils.WireJson.write(message),
                    key = a.getSessionId().toString();
            if (!a.isStoreSent()) {
                kafka.send(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, key, payload)
                        .get(10, TimeUnit.SECONDS);
                mark(id, token, "store_sent");
            }
            if (!a.isPushSent()) {
                kafka.send(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH, key, payload)
                        .get(10, TimeUnit.SECONDS);
                mark(id, token, "push_sent");
            }
            // 不发布 ai-request-topic，提醒不会再次触发机器人回复。
            markSent(id, token);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            boolean email = "EMAIL".equals(a.getKind());
            boolean terminal =
                    email
                            || error instanceof cn.qingliao.common.exception.BusinessException
                            || a.getAttempts() >= 5;
            String text =
                    email
                            ? "邮件投递结果未确认，请核对邮箱中的收件情况"
                            : terminal ? "权限或参数校验失败，或已达重试上限；请检查目标会话后重试" : "消息投递暂未确认，将使用相同消息编号重试";
            repo.jdbc()
                    .update(
                            "UPDATE agent_action SET"
                                + " status=?,next_attempt_at=?,lease_until=0,last_error=?,updated_at=?"
                                + " WHERE action_id=? AND lease_token=? AND status='RUNNING'",
                            terminal ? "FAILED" : "PENDING",
                            clock.millis()
                                    + Math.min(60000, 1000L * (1L << Math.min(a.getAttempts(), 6))),
                            text,
                            clock.millis(),
                            id,
                            token);
        }
    }

    private void sendEmail(Action action) {
        if (sender == null || sender.isBlank()) throw new IllegalStateException("未配置发件邮箱");
        String[] mail = action.getContent().split("\n", 3);
        var message = new SimpleMailMessage();
        message.setFrom(sender);
        message.setTo(mail[0]);
        message.setSubject(mail[1]);
        message.setText(mail[2]);
        mailSender.send(message);
    }

    private void markSent(long id, String token) {
        repo.jdbc()
                .update(
                        "UPDATE agent_action SET"
                            + " status='SENT',lease_until=0,last_error='',updated_at=? WHERE"
                            + " action_id=? AND lease_token=? AND status='RUNNING'",
                        clock.millis(),
                        id,
                        token);
    }

    private void mark(long id, String token, String column) {
        int updated =
                repo.jdbc()
                        .update(
                                "UPDATE agent_action SET "
                                        + column
                                        + "=true,updated_at=? WHERE action_id=? AND lease_token=?"
                                        + " AND status='RUNNING'",
                                clock.millis(),
                                id,
                                token);
        if (updated != 1) throw new IllegalStateException("执行租约已失效");
    }
}
