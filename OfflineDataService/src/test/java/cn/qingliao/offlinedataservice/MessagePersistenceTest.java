package cn.qingliao.offlinedataservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.AssistantHistory;
import cn.qingliao.common.model.dto.KnowledgeSession;
import cn.qingliao.common.model.dto.MessageBody;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.common.model.vo.MessageResponse;
import cn.qingliao.common.security.CurrentUser;
import cn.qingliao.offlinedataservice.client.CanalClient;
import cn.qingliao.offlinedataservice.client.UserServiceClient;
import cn.qingliao.offlinedataservice.mapper.MessageMapper;
import cn.qingliao.offlinedataservice.model.dto.HistoryMessageRequest;
import cn.qingliao.offlinedataservice.model.entity.Message;
import cn.qingliao.offlinedataservice.service.impl.MessageServiceImpl;
import cn.qingliao.offlinedataservice.utils.MessageResponses;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

/** 使用真实 H2 + MyBatis 执行 SQL；不能代替 MySQL/Redis/Kafka 的整体联调。 */
class MessagePersistenceTest {
    private PooledDataSource dataSource;
    private SqlSessionFactory factory;
    private SqlSession session;
    private MessageMapper mapper;
    private MessageServiceImpl service;
    private UserServiceClient users;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private final long timestamp = System.currentTimeMillis() - 60000;

    @BeforeEach
    void prepare() throws Exception {
        dataSource =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        try (Connection connection = dataSource.getConnection()) {
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE message(message_id BIGINT PRIMARY KEY,sender_id"
                                + " BIGINT,session_id BIGINT,type INT,content TEXT,reply_id"
                                + " BIGINT,session_type INT,created_time TIMESTAMP(3),updated_time"
                                + " TIMESTAMP(3))");
        }
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        config.addMapper(MessageMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(config);
        session = factory.openSession(true);
        mapper = spy(session.getMapper(MessageMapper.class));
        users = mock(UserServiceClient.class);
        when(users.getSessionIdsByUserId(1L)).thenReturn(List.of(99L));
        service = new MessageServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "users", users);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.USER_ID, 1L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void close() {
        RequestContextHolder.resetRequestAttributes();
        if (session != null) session.close();
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @Test
    void duplicateDeliveryKeepsOneOriginalRow() {
        mapper.insertIdempotently(message(1, timestamp, "first"));
        mapper.insertIdempotently(message(1, timestamp, "retry"));
        assertEquals(1, mapper.selectCount(null));
        assertEquals("first", mapper.selectById(1L).getContent());
    }

    @Test
    void assistantHistoryIsPagedAndRechecksMembershipBeforeReturning() {
        for (int i = 1; i <= 21; i++) mapper.insertIdempotently(message(i, timestamp, "消息" + i));
        when(users.knowledgeAccess(1L, 99L)).thenReturn(new KnowledgeSession(true, false));
        when(users.getUserNickName(99L)).thenReturn(Map.of(1L, "演示用户"));
        var latest = service.getAssistantHistory(new AssistantHistory.Query(1, 99, null, null));
        assertEquals(20, latest.messages().size());
        assertEquals("消息2", latest.messages().get(0).text());
        assertEquals("演示用户", latest.messages().get(0).sender());
        assertEquals("2", latest.nextBeforeMessageId());

        var earlier =
                service.getAssistantHistory(
                        new AssistantHistory.Query(1, 99, latest.nextBeforeTime(), 2L));
        assertEquals(
                List.of("消息1"),
                earlier.messages().stream().map(AssistantHistory.Entry::text).toList());

        when(users.knowledgeAccess(1L, 99L))
                .thenReturn(new KnowledgeSession(true, false), new KnowledgeSession(false, false));
        assertThrows(
                cn.qingliao.common.exception.BusinessException.class,
                () -> service.getAssistantHistory(new AssistantHistory.Query(1, 99, null, null)));
    }

    @Test
    void concurrentDuplicateDeliveryStillKeepsOneRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> attempts = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                attempts.add(
                        () -> {
                            try (SqlSession worker = factory.openSession(true)) {
                                worker.getMapper(MessageMapper.class)
                                        .insertIdempotently(message(7, timestamp, "same"));
                            }
                            return null;
                        });
            for (Future<Void> result : pool.invokeAll(attempts)) result.get(10, TimeUnit.SECONDS);
            assertEquals(1, mapper.selectCount(null));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void emptyHotCacheStillReturnsRecentDatabaseMessages() {
        mapper.insertIdempotently(message(1, timestamp, "recent"));
        HistoryMessageRequest request = history(timestamp + 1, null);
        assertEquals(
                List.of(1L),
                service.getHistoryMessages(request).stream()
                        .map(value -> value.getMessageId())
                        .toList());
    }

    @Test
    void timestampAndIdCursorDoesNotSkipSameMillisecondRows() {
        for (int i = 1; i <= 4; i++) mapper.insertIdempotently(message(i, timestamp, "same-time"));
        HistoryMessageRequest first = history(timestamp + 1, null);
        first.setLimit(2);
        var page1 = service.getHistoryMessages(first);
        assertEquals(List.of(4L, 3L), page1.stream().map(value -> value.getMessageId()).toList());
        HistoryMessageRequest next = history(timestamp, 3L);
        next.setLimit(2);
        assertEquals(
                List.of(2L, 1L),
                service.getHistoryMessages(next).stream()
                        .map(value -> value.getMessageId())
                        .toList());
    }

    @Test
    void nonMemberCannotReadHistoryAndLimitIsBounded() {
        HistoryMessageRequest request = history(timestamp + 1, null);
        request.setSessionId(100L);
        assertThrows(
                cn.qingliao.common.exception.BusinessException.class,
                () -> service.getHistoryMessages(request));
        request.setSessionId(99L);
        request.setLimit(100000);
        assertThrows(
                cn.qingliao.common.exception.BusinessException.class,
                () -> service.getHistoryMessages(request));
        verifyNoInteractions(redis);
        verify(mapper, never()).selectList(any());
    }

    @Test
    void fullHotPageReturnsWithoutAnyMessageTableQuery() {
        cached(
                message(1, timestamp - 2, "one"),
                message(2, timestamp - 1, "two"),
                message(3, timestamp, "three"));
        HistoryMessageRequest request = history(timestamp + 1, null);
        request.setLimit(2);
        assertEquals(List.of(3L, 2L), ids(service.getHistoryMessages(request)));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void partialHotPageFindsMissingMessagesInsideSevenDayWindow() {
        for (int i = 1; i <= 3; i++)
            mapper.insertIdempotently(message(i, timestamp + i, "row-" + i));
        cached(message(3, timestamp + 3, "three"), message(1, timestamp + 1, "one"));
        HistoryMessageRequest request = history(timestamp + 4, null);
        request.setLimit(3);
        assertEquals(List.of(3L, 2L, 1L), ids(service.getHistoryMessages(request)));
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void coldPageSkipsRedisEntirely() {
        long cold = System.currentTimeMillis() - CommonConstant.SEVEN_DAYS_MILLIS - 60000;
        mapper.insertIdempotently(message(1, cold - 1, "cold"));
        assertEquals(List.of(1L), ids(service.getHistoryMessages(history(cold, null))));
        verifyNoInteractions(redis);
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void partialHotPageCanContinueAcrossColdBoundary() {
        long boundary = System.currentTimeMillis() - CommonConstant.SEVEN_DAYS_MILLIS;
        mapper.insertIdempotently(message(3, timestamp, "hot"));
        mapper.insertIdempotently(message(2, boundary - 1000, "cold-2"));
        mapper.insertIdempotently(message(1, boundary - 2000, "cold-1"));
        cached(message(3, timestamp, "hot"));
        HistoryMessageRequest request = history(timestamp + 1, null);
        request.setLimit(3);
        assertEquals(List.of(3L, 2L, 1L), ids(service.getHistoryMessages(request)));
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void redisFailureFallsBackToDatabase() {
        mapper.insertIdempotently(message(1, timestamp, "database"));
        when(redis.opsForZSet()).thenThrow(new IllegalStateException("Redis unavailable"));
        assertEquals(List.of(1L), ids(service.getHistoryMessages(history(timestamp + 1, null))));
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void malformedCacheFallsBackToDatabase() {
        mapper.insertIdempotently(message(1, timestamp, "database"));
        when(zset.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of("broken-json"));
        assertEquals(List.of(1L), ids(service.getHistoryMessages(history(timestamp + 1, null))));
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void cacheScoreTiesDoNotSubstituteJsonOrderForMessageIdOrder() {
        List<Message> messages = new ArrayList<>();
        for (long id : new long[] {8, 9, 100, 101, 102}) {
            Message value = message(id, timestamp, "same-time");
            messages.add(value);
            mapper.insertIdempotently(value);
        }
        cached(messages.toArray(Message[]::new));
        HistoryMessageRequest first = history(timestamp + 1, null);
        first.setLimit(2);
        assertEquals(List.of(102L, 101L), ids(service.getHistoryMessages(first)));
        HistoryMessageRequest next = history(timestamp, 101L);
        next.setLimit(2);
        assertEquals(List.of(100L, 9L), ids(service.getHistoryMessages(next)));
        verify(mapper, times(2)).selectList(any());
    }

    @Test
    void sameMillisecondPageCanHitCacheWhenWholeTieGroupFits() {
        cached(message(9, timestamp, "nine"), message(100, timestamp, "hundred"));
        HistoryMessageRequest request = history(timestamp, 101L);
        request.setLimit(2);
        assertEquals(List.of(100L, 9L), ids(service.getHistoryMessages(request)));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void canalAndAfterCommitWriteIdenticalMembersForTextAndRedPackets() {
        CanalClient canal = new CanalClient();
        ReflectionTestUtils.setField(canal, "stringRedisTemplate", redis);
        for (int type : new int[] {0, 3}) {
            MessageBody body = new MessageBody();
            body.setContent(type == 3 ? "packet" : "text");
            body.setReplyId(7L);
            MessageRequest request = new MessageRequest();
            request.setMessageId(100L + type);
            request.setSessionId(99L);
            request.setSenderId(1L);
            request.setSessionType(0);
            request.setCreatedTime(new Date(timestamp));
            request.setType(type);
            request.setBody(body);
            service.saveMessageToMySQL(request);
            Map<String, String> row = new HashMap<>();
            row.put("message_id", request.getMessageId().toString());
            row.put("session_id", "99");
            row.put("sender_id", "1");
            row.put("session_type", "0");
            row.put("type", Integer.toString(type));
            row.put("reply_id", "7");
            row.put("content", type == 3 ? JSONUtil.toJsonStr(body) : "text");
            row.put("created_time", new java.sql.Timestamp(timestamp).toString());
            MessageResponse response =
                    ReflectionTestUtils.invokeMethod(canal, "buildMessageFromMap", row);
            ReflectionTestUtils.invokeMethod(canal, "storeMessageToRedis", response);
            String expected =
                    JSONUtil.toJsonStr(
                            MessageResponses.fromEntity(mapper.selectById(request.getMessageId())));
            verify(zset, times(2)).add("session:99", expected, timestamp);
            assertEquals(body, response.getBody());
        }
    }

    private List<Long> ids(List<MessageResponse> messages) {
        return messages.stream().map(MessageResponse::getMessageId).toList();
    }

    @Test
    void offlineHotMessagesUseRedisWithoutMessageQuery() {
        when(zset.rangeByScore(eq("session:99"), anyDouble(), anyDouble()))
                .thenReturn(
                        Set.of(
                                JSONUtil.toJsonStr(
                                        MessageResponses.fromEntity(
                                                message(1, timestamp, "hot")))));
        var request = new cn.qingliao.offlinedataservice.model.dto.OfflineMessageRequest();
        request.setOfflineTime(timestamp - 1);
        request.setUserId(999L); // 身份仍取认证用户，不接受请求体冒充。
        assertEquals(List.of(1L), ids(service.getOfflineMessages(request).get(99L)));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void offlineMixedWindowReadsOnlyColdRowsAndIncludesBoundary() {
        long boundary = timestamp - CommonConstant.SEVEN_DAYS_MILLIS;
        mapper.insertIdempotently(message(1, boundary - 1, "cold"));
        mapper.insertIdempotently(message(3, boundary + 1, "not-in-cache"));
        when(zset.rangeByScore("session:99", (double) boundary, Double.MAX_VALUE))
                .thenReturn(
                        Set.of(
                                JSONUtil.toJsonStr(
                                        MessageResponses.fromEntity(
                                                message(2, boundary, "boundary")))));
        List<MessageResponse> values =
                ReflectionTestUtils.invokeMethod(
                        service, "getMessagesAfter", 99L, boundary - 2, boundary);
        assertEquals(List.of(1L, 2L), ids(values));
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void offlineMissingOrFailedCacheFallsBackAcrossEntireWindow() {
        mapper.insertIdempotently(message(1, timestamp, "recent"));
        var request = new cn.qingliao.offlinedataservice.model.dto.OfflineMessageRequest();
        request.setOfflineTime(timestamp - 1);
        when(zset.rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(Set.of());
        assertEquals(List.of(1L), ids(service.getOfflineMessages(request).get(99L)));
        when(zset.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("down"));
        assertEquals(List.of(1L), ids(service.getOfflineMessages(request).get(99L)));
    }

    @Test
    void summaryKeepsOriginalTimeFormatAndDoesNotSilentlyTruncateAt1000() {
        var ai = mock(cn.qingliao.offlinedataservice.client.AiServiceClient.class);
        ReflectionTestUtils.setField(service, "ai", ai);
        when(ai.chatSummary(any()))
                .thenReturn(cn.qingliao.common.common.ResultUtils.success("summary"));
        when(users.getUserNickName(99L)).thenReturn(Map.of(1L, "Alice"));
        for (int i = 1; i <= 1001; i++)
            mapper.insertIdempotently(message(i, timestamp + i, "m" + i));
        var request = new cn.qingliao.offlinedataservice.model.dto.SessionSummaryRequest();
        request.setSessionId(99L);
        request.setHours(169);
        assertEquals("summary", service.getSummary(request));
        var log =
                org.mockito.ArgumentCaptor.forClass(
                        cn.qingliao.common.model.dto.ConversationSummaryInput.class);
        verify(ai).chatSummary(log.capture());
        String history = log.getValue().historyLog();
        assertEquals(1001, history.lines().count());
        assertTrue(
                history.startsWith(
                        "[Alice] "
                                + cn.qingliao.common.utils.FormatDateUtil.formatDate(
                                        new Date(timestamp + 1))
                                + "：m1\n"));
        assertTrue(history.endsWith("：m1001\n"));
        when(ai.chatSummary(any()))
                .thenReturn(
                        new cn.qingliao.common.common.BaseResponse<String>(
                                cn.qingliao.common.common.ErrorCode.SYSTEM_ERROR));
        assertThrows(
                cn.qingliao.common.exception.BusinessException.class,
                () -> service.getSummary(request));
    }

    /** 模拟 ZSET 的 score/JSON 字典逆序，故意保留同 score 与 ID 次序不一致的情况。 */
    private void cached(Message... messages) {
        List<MessageResponse> values =
                Arrays.stream(messages).map(MessageResponses::fromEntity).toList();
        when(zset.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenAnswer(
                        call -> {
                            double min = call.getArgument(1);
                            double max = call.getArgument(2);
                            long offset = call.getArgument(3);
                            long count = call.getArgument(4);
                            LinkedHashSet<String> result = new LinkedHashSet<>();
                            values.stream()
                                    .filter(
                                            value ->
                                                    Long.parseLong(value.getCreatedTime()) >= min
                                                            && Long.parseLong(
                                                                            value.getCreatedTime())
                                                                    <= max)
                                    .sorted(
                                            Comparator.comparingLong(
                                                            (MessageResponse value) ->
                                                                    Long.parseLong(
                                                                            value.getCreatedTime()))
                                                    .thenComparing(
                                                            value -> JSONUtil.toJsonStr(value))
                                                    .reversed())
                                    .skip(offset)
                                    .limit(count)
                                    .map(JSONUtil::toJsonStr)
                                    .forEach(result::add);
                            return result;
                        });
    }

    private HistoryMessageRequest history(long before, Long id) {
        HistoryMessageRequest request = new HistoryMessageRequest();
        request.setSessionId(99L);
        request.setBeforeTime(before);
        request.setBeforeMessageId(id);
        return request;
    }

    private Message message(long id, long time, String text) {
        Message message = new Message();
        message.setMessageId(id);
        message.setSenderId(1L);
        message.setSessionId(99L);
        message.setType(0);
        message.setSessionType(0);
        message.setContent(text);
        message.setCreatedTime(new Date(time));
        return message;
    }
}
