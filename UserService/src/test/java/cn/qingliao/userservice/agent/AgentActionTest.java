package cn.qingliao.userservice.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.AgentActions.*;
import cn.qingliao.userservice.model.entity.Session;
import cn.qingliao.userservice.model.entity.UserSession;
import cn.qingliao.userservice.service.SessionService;
import cn.qingliao.userservice.service.UserSessionService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 实际迁移SQL和H2事务/条件更新；模型、Kafka、Redis不连接外部服务。 */
class AgentActionTest {
    AgentActionRepository repo;
    AgentActionService service;
    AgentActionDelivery delivery;
    SessionService sessions;
    UserSessionService members;
    SessionSendAccess access;
    KafkaTemplate<String, String> kafka;
    MutableClock clock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("05-agent-actions.sql")).execute(db);
        repo = new AgentActionRepository(new JdbcTemplate(db));
        sessions = mock(SessionService.class);
        members = mock(UserSessionService.class);
        access = mock(SessionSendAccess.class);
        kafka = mock(KafkaTemplate.class);
        clock = new MutableClock(System.currentTimeMillis());
        Session group = new Session();
        group.setSessionId(10L);
        group.setType(1);
        group.setStatus(0);
        group.setName("项目群");
        Session bot = new Session();
        bot.setSessionId(20L);
        bot.setType(2);
        bot.setStatus(0);
        when(sessions.getById(10L)).thenReturn(group);
        when(sessions.getById(20L)).thenReturn(bot);
        UserSession me = new UserSession();
        me.setUserId(1001L);
        me.setSessionId(20L);
        UserSession peer = new UserSession();
        peer.setUserId(CommonConstant.AI_ID);
        peer.setSessionId(20L);
        when(members.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(me, peer));
        when(access.canSend(anyLong(), anyLong(), anyInt(), nullable(Long.class))).thenReturn(true);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new AgentActionService(repo, sessions, members, access, clock);
        delivery = new AgentActionDelivery(repo, service, sessions, kafka, clock);
    }

    Action draftMessage(String key) {
        return service.draft(1001, new Draft(key, "MESSAGE", 10L, "接口已更新", null));
    }

    @Test
    void draftHasNoSideEffectAndRequestKeyCannotChangeContent() {
        Action a = draftMessage("same");
        assertEquals("DRAFT", a.getStatus());
        verifyNoInteractions(kafka);
        assertEquals(a.getActionId(), draftMessage("same").getActionId());
        assertThrows(
                RuntimeException.class,
                () -> service.draft(1001, new Draft("same", "MESSAGE", 10L, "另一条正文", null)));
    }

    @Test
    void confirmedMessageIsDeliveredOnceWithStableIdAndNoBotRequest() throws Exception {
        Action a = draftMessage("once");
        service.confirm(1001, a.getActionId(), 0);
        service.confirm(1001, a.getActionId(), 0);
        delivery.execute(a.getActionId(), 0);
        delivery.execute(a.getActionId(), 0);
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("store-topic"), eq("10"), captured.capture());
        verify(kafka).send(eq("message-topic"), eq("10"), eq(captured.getValue()));
        verify(kafka, never()).send(eq("ai-request-topic"), anyString(), anyString());
        var body = new ObjectMapper().readTree(captured.getValue());
        assertEquals(a.getMessageId().longValue(), body.path("messageId").asLong());
        assertEquals(1001, body.path("senderId").asLong());
        assertTrue(body.path("content").asText().startsWith("[助手代发]"));
        assertEquals("SENT", repo.find(a.getActionId()).getStatus());
    }

    @Test
    void emailIsDeliveredOnlyAfterConfirmation() {
        var mailSender = mock(org.springframework.mail.javamail.JavaMailSender.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                delivery, "mailSender", mailSender);
        org.springframework.test.util.ReflectionTestUtils.setField(
                delivery, "sender", "sender@example.com");
        Action task =
                service.draft(
                        1001,
                        new Draft(
                                "mail",
                                "EMAIL",
                                20L,
                                "recipient@example.com\n进度汇报\n本周已完成验证。",
                                null));
        delivery.execute(task.getActionId(), 0);
        verifyNoInteractions(mailSender);
        assertEquals("DRAFT", repo.find(task.getActionId()).getStatus());
        service.confirm(1001, task.getActionId(), 0);
        delivery.execute(task.getActionId(), 0);
        delivery.execute(task.getActionId(), 0);
        var sent =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.mail.SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertArrayEquals(new String[] {"recipient@example.com"}, sent.getValue().getTo());
        assertEquals("进度汇报", sent.getValue().getSubject());
        assertEquals("本周已完成验证。", sent.getValue().getText());
        assertEquals("SENT", repo.find(task.getActionId()).getStatus());
        verifyNoInteractions(kafka);
    }

    @Test
    void futurePersonalReminderIsNotDeliveredEarlyAndDoesNotInvokeAgentAgain() throws Exception {
        Action a =
                service.draft(
                        1001, new Draft("remind", "REMINDER", 20L, "提交简历", clock.millis() + 60000));
        service.confirm(1001, a.getActionId(), 0);
        delivery.execute(a.getActionId(), 0);
        verifyNoInteractions(kafka);
        clock.now += 61000;
        delivery.execute(a.getActionId(), 0);
        var value = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("store-topic"), eq("20"), value.capture());
        var message = new ObjectMapper().readTree(value.getValue());
        assertEquals(CommonConstant.AI_ID.longValue(), message.path("senderId").asLong());
        assertEquals(1001, message.path("receiverId").asLong());
        assertEquals(clock.millis(), message.path("createdTime").asLong());
        verify(kafka, never()).send(eq("ai-request-topic"), anyString(), anyString());
    }

    @Test
    void rescheduleInvalidatesOldEventAndRequiresNewConfirmation() {
        Action a =
                service.draft(
                        1001, new Draft("edit", "REMINDER", 20L, "旧内容", clock.millis() + 10000));
        service.confirm(1001, a.getActionId(), 0);
        Action changed =
                service.edit(1001, a.getActionId(), new Edit(0, "新内容", clock.millis() + 90000));
        assertEquals("DRAFT", changed.getStatus());
        clock.now += 11000;
        delivery.execute(a.getActionId(), 0);
        delivery.execute(a.getActionId(), 1);
        verifyNoInteractions(kafka);
        assertThrows(RuntimeException.class, () -> service.confirm(1001, a.getActionId(), 0));
        service.confirm(1001, a.getActionId(), 1);
        clock.now += 90000;
        delivery.execute(a.getActionId(), 1);
        assertEquals("SENT", repo.find(a.getActionId()).getStatus());
    }

    @Test
    void cancellationAndOwnershipAreEnforced() {
        Action a = draftMessage("cancel");
        service.confirm(1001, a.getActionId(), 0);
        assertThrows(RuntimeException.class, () -> service.cancel(2002, a.getActionId(), 0));
        assertThrows(RuntimeException.class, () -> service.owned(2002, a.getActionId()));
        service.cancel(1001, a.getActionId(), 0);
        delivery.execute(a.getActionId(), 0);
        verifyNoInteractions(kafka);
        assertEquals("CANCELLED", repo.find(a.getActionId()).getStatus());
    }

    @Test
    void permissionIsRecheckedWhenTaskFires() {
        Action a = draftMessage("leave");
        service.confirm(1001, a.getActionId(), 0);
        when(access.canSend(anyLong(), anyLong(), anyInt(), nullable(Long.class)))
                .thenReturn(false);
        delivery.execute(a.getActionId(), 0);
        assertEquals("FAILED", repo.find(a.getActionId()).getStatus());
        verifyNoInteractions(kafka);
    }

    @Test
    void partialPublishRetriesOnlyMissingTopicWithIdenticalMessage() {
        Action a = draftMessage("partial");
        service.confirm(1001, a.getActionId(), 0);
        when(kafka.send(eq("message-topic"), anyString(), anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("offline")),
                        CompletableFuture.completedFuture(null));
        delivery.execute(a.getActionId(), 0);
        Action failed = repo.find(a.getActionId());
        assertTrue(failed.isStoreSent());
        assertFalse(failed.isPushSent());
        assertEquals("PENDING", failed.getStatus());
        assertThrows(
                RuntimeException.class,
                () -> service.edit(1001, a.getActionId(), new Edit(0, "禁止更换已投递正文", null)));
        assertThrows(RuntimeException.class, () -> service.cancel(1001, a.getActionId(), 0));
        clock.now += 10000;
        delivery.execute(a.getActionId(), 0);
        verify(kafka, times(1)).send(eq("store-topic"), anyString(), anyString());
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(eq("message-topic"), anyString(), captured.capture());
        assertEquals(captured.getAllValues().get(0), captured.getAllValues().get(1));
        assertEquals("SENT", repo.find(a.getActionId()).getStatus());
    }

    @Test
    void concurrentConsumersOnlyClaimOneExecution() throws Exception {
        Action a = draftMessage("race");
        service.confirm(1001, a.getActionId(), 0);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 12; i++)
                jobs.add(pool.submit(() -> delivery.execute(a.getActionId(), 0)));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        verify(kafka, times(1)).send(eq("store-topic"), anyString(), anyString());
        verify(kafka, times(1)).send(eq("message-topic"), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredLeaseCanBeRecoveredFromDatabaseWithoutOriginalRedisEntry() {
        Action a = draftMessage("repair");
        service.confirm(1001, a.getActionId(), 0);
        repo.jdbc()
                .update(
                        "UPDATE agent_action SET status='RUNNING',lease_until=0,attempts=1 WHERE"
                                + " action_id=?",
                        a.getActionId());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        AgentActionScheduler scheduler = new AgentActionScheduler(repo, delivery, redis, kafka);
        scheduler.repair();
        assertEquals("PENDING", repo.find(a.getActionId()).getStatus());
        verify(zset).add(eq(AgentActionScheduler.KEY), eq(a.getActionId() + ":0"), anyDouble());
        clock.now = System.currentTimeMillis() + 1000;
        delivery.execute(a.getActionId(), 0);
        assertEquals("SENT", repo.find(a.getActionId()).getStatus());
    }

    @Test
    void invalidTimesAndWrongTargetAreRejected() {
        assertThrows(
                RuntimeException.class,
                () ->
                        service.draft(
                                1001, new Draft("past", "REMINDER", 20L, "a", clock.millis() - 1)));
        assertThrows(
                RuntimeException.class,
                () -> service.draft(1001, new Draft("missing", "REMINDER", 20L, "a", null)));
        assertThrows(
                RuntimeException.class,
                () ->
                        service.draft(
                                1001,
                                new Draft("wrong", "REMINDER", 10L, "a", clock.millis() + 10000)));
    }

    static class MutableClock extends Clock {
        volatile long now;

        MutableClock(long now) {
            this.now = now;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
