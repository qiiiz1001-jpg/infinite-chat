package cn.qingliao.userservice.agent;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 沿用红包 ZSET + Lua + Kafka；数据库任务补偿取走后丢失的事件。 */
@Component
@Slf4j
@ConditionalOnProperty(
        name = "chat.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentActionScheduler {
    public static final String KEY = "agent-action:due";
    public static final String TOPIC = "agent-action-due-topic";
    private final AgentActionRepository repo;
    private final AgentActionDelivery delivery;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final DefaultRedisScript<List> scan =
            new DefaultRedisScript<>(
                    "local t=redis.call('TIME'); local n=t[1]*1000+math.floor(t[2]/1000); local"
                        + " a=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',n,'LIMIT',0,100); for _,v"
                        + " in ipairs(a) do redis.call('ZREM',KEYS[1],v) end; return a",
                    List.class);

    public AgentActionScheduler(
            AgentActionRepository repo,
            AgentActionDelivery delivery,
            StringRedisTemplate redis,
            KafkaTemplate<String, String> kafka) {
        this.repo = repo;
        this.delivery = delivery;
        this.redis = redis;
        this.kafka = kafka;
    }

    @org.springframework.context.annotation.Bean
    public org.apache.kafka.clients.admin.NewTopic agentActionDueTopic() {
        return org.springframework.kafka.config.TopicBuilder.name(TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Scheduled(fixedDelayString = "${chat.agent.repair-ms:15000}")
    public void repair() {
        try {
            long now = System.currentTimeMillis();
            repo.jdbc()
                    .update(
                            "UPDATE agent_action SET status=CASE WHEN attempts>=5 OR kind='EMAIL'"
                                + " THEN 'FAILED' ELSE 'PENDING'"
                                + " END,next_attempt_at=?,lease_until=0,last_error='执行租约超时，待核对或重试',updated_at=?"
                                + " WHERE status='RUNNING' AND lease_until<?",
                            now,
                            now,
                            now);
            for (var a : repo.due(now + 30000))
                redis.opsForZSet()
                        .add(KEY, a.getActionId() + ":" + a.getVersion(), a.getNextAttemptAt());
        } catch (Exception e) {
            log.error("提醒任务补偿失败，请检查数据库迁移与Redis", e);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        try {
            long now = System.currentTimeMillis();
            for (var a : repo.due(now))
                redis.opsForZSet()
                        .add(KEY, a.getActionId() + ":" + a.getVersion(), a.getNextAttemptAt());
            List<?> due = redis.execute(scan, List.of(KEY));
            if (due != null)
                for (Object item : due)
                    kafka.send(TOPIC, item.toString(), item.toString())
                            .whenComplete(
                                    (ok, error) -> {
                                        if (error != null)
                                            log.warn("提醒事件投递失败，将由数据库扫描补偿: {}", item, error);
                                    });
        } catch (Exception e) {
            log.error("提醒任务调度失败", e);
        }
    }

    @KafkaListener(topics = TOPIC, groupId = "agent-action-executor")
    public void consume(String event) {
        if (event == null || !event.matches("[0-9]+:[0-9]+")) {
            log.warn("忽略非法提醒事件");
            return;
        }
        String[] parts = event.split(":");
        try {
            delivery.execute(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException invalid) {
            log.warn("忽略越界提醒事件");
        }
    }
}
