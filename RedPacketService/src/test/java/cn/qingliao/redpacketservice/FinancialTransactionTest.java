package cn.qingliao.redpacketservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.qingliao.redpacketservice.client.UserServiceClient;
import cn.qingliao.redpacketservice.mapper.*;
import cn.qingliao.redpacketservice.model.entity.RedPacket;
import cn.qingliao.redpacketservice.service.RedPacketService;
import cn.qingliao.redpacketservice.service.RedPacketValidationService;
import cn.qingliao.redpacketservice.service.impl.RedPacketServiceImpl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.*;
import java.util.concurrent.*;

/** 真实 H2/MyBatis/Spring 数据库事务，模拟 Redis/Kafka；重点验证账务副作用只执行一次。 */
class FinancialTransactionTest {
    private PooledDataSource dataSource;
    private JdbcTemplate jdbc;
    private RedPacketService service;
    private RedPacketMapper packetMapper;
    private RedPacketServiceImpl target;
    private StringRedisTemplate redis;
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked", "deprecation"})
    void prepare() {
        dataSource =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE user_balance(user_id BIGINT PRIMARY KEY,balance BIGINT NOT"
                        + " NULL,created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_time"
                        + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE balance_log(balance_log_id BIGINT PRIMARY KEY,user_id BIGINT,amount"
                        + " BIGINT,type INT,related_id BIGINT,created_time TIMESTAMP,updated_time"
                        + " TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE red_packet_receive(red_packet_receive_id BIGINT PRIMARY"
                        + " KEY,red_packet_id BIGINT,receiver_id BIGINT,amount BIGINT,received_at"
                        + " TIMESTAMP,created_time TIMESTAMP,updated_time TIMESTAMP,CONSTRAINT"
                        + " uk_packet_receiver UNIQUE(red_packet_id,receiver_id))");
        jdbc.execute(
                "CREATE TABLE red_packet(red_packet_id BIGINT PRIMARY KEY,sender_id"
                    + " BIGINT,session_id BIGINT,session_type INT,red_packet_wrapper_text"
                    + " VARCHAR(128),red_packet_type INT,total_amount BIGINT,total_count INT,status"
                    + " INT,created_time TIMESTAMP,updated_time TIMESTAMP)");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(
                new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(RedPacketMapper.class);
        config.addMapper(UserBalanceMapper.class);
        config.addMapper(BalanceLogMapper.class);
        config.addMapper(RedPacketReceiveMapper.class);
        SqlSessionTemplate sql =
                new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        packetMapper = sql.getMapper(RedPacketMapper.class);
        redis = mock(StringRedisTemplate.class);
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        when(redis.opsForList()).thenReturn(mock(ListOperations.class));
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        DefaultRedisScript<Long> refund = new DefaultRedisScript<>();
        when(redis.execute(same(refund), anyList())).thenReturn(50L);
        target =
                new RedPacketServiceImpl(
                        packetMapper,
                        sql.getMapper(UserBalanceMapper.class),
                        sql.getMapper(BalanceLogMapper.class),
                        redis,
                        kafka,
                        mock(RedPacketValidationService.class),
                        refund,
                        new DefaultRedisScript<>(),
                        sql.getMapper(RedPacketReceiveMapper.class),
                        mock(UserServiceClient.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(RedPacketService.class);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(dataSource),
                        new AnnotationTransactionAttributeSource()));
        service = (RedPacketService) proxy.getProxy();
    }

    @AfterEach
    void close() {
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @Test
    void sendAndCompensationPreserveOriginalClientMessageId() {
        HashOperations<String, Object, Object> hashes = redis.opsForHash();
        Map<String, Object> cache = new HashMap<>();
        doAnswer(
                        call -> {
                            cache.put(
                                    call.getArgument(0) + ":" + call.getArgument(1),
                                    call.getArgument(2));
                            return null;
                        })
                .when(hashes)
                .put(anyString(), any(), any());
        when(hashes.get(anyString(), any()))
                .thenAnswer(call -> cache.get(call.getArgument(0) + ":" + call.getArgument(1)));
        jdbc.update("INSERT INTO user_balance(user_id,balance) VALUES(1,1000)");
        var request = new cn.qingliao.redpacketservice.model.dto.RedPacketSendRequest();
        request.setSenderId(1L);
        request.setReceiverId(2L);
        request.setSessionId(99L);
        request.setSessionType(0);
        request.setType(3);
        request.setClientMessageId("client-packet-123");
        var body = new cn.qingliao.redpacketservice.model.dto.RedPacketBody();
        body.setRedPacketType(0);
        body.setTotalCount(1);
        body.setTotalAmount(new java.math.BigDecimal("1.00"));
        body.setRedPacketWrapperText("hello");
        request.setBody(body);
        var result = service.sendRedPacket(request);
        target.publishPending(result.getRedPacketId());
        var stored = org.mockito.ArgumentCaptor.forClass(String.class);
        var pushed = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2))
                .send(
                        eq(cn.qingliao.common.constant.CommonConstant.KAFKA_MESSAGE_TOPIC_STORE),
                        eq("99"),
                        stored.capture());
        verify(kafka, times(2))
                .send(
                        eq(cn.qingliao.common.constant.CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH),
                        eq("99"),
                        pushed.capture());
        assertEquals(stored.getAllValues(), pushed.getAllValues());
        assertEquals(stored.getAllValues().get(0), stored.getAllValues().get(1));
        var payload = cn.hutool.json.JSONUtil.parseObj(stored.getValue());
        assertEquals("client-packet-123", payload.getStr("clientMessageId"));
        assertEquals(result.getMessageId(), payload.getLong("messageId"));
        assertEquals(
                900L,
                jdbc.queryForObject(
                        "SELECT balance FROM user_balance WHERE user_id=1", Long.class));
    }

    @Test
    void repeatedAndConcurrentEventsCreditExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> events = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                events.add(
                        () -> {
                            service.handleRedPacketReceive(2L, 9L, 50L, 1700000000000L);
                            return null;
                        });
            for (Future<Void> event : pool.invokeAll(events)) event.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM red_packet_receive", Integer.class));
        assertEquals(
                50L,
                jdbc.queryForObject(
                        "SELECT balance FROM user_balance WHERE user_id=2", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM balance_log", Integer.class));
    }

    @Test
    void ledgerFailureRollsBackReceiptAndBalanceThenRetrySucceeds() {
        jdbc.execute("ALTER TABLE balance_log ADD CONSTRAINT injected_failure CHECK(amount<100)");
        assertThrows(
                RuntimeException.class,
                () -> service.handleRedPacketReceive(2L, 9L, 200L, 1700000000000L));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM red_packet_receive", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_balance", Integer.class));
        jdbc.execute("ALTER TABLE balance_log DROP CONSTRAINT injected_failure");
        service.handleRedPacketReceive(2L, 9L, 200L, 1700000000000L);
        service.handleRedPacketReceive(2L, 9L, 200L, 1700000000000L);
        assertEquals(
                200L,
                jdbc.queryForObject(
                        "SELECT balance FROM user_balance WHERE user_id=2", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM balance_log", Integer.class));
    }

    @Test
    void repeatedExpirationDoesNotRefundTwice() {
        RedPacket packet = new RedPacket();
        packet.setRedPacketId(9L);
        packet.setSenderId(1L);
        packet.setSessionId(99L);
        packet.setStatus(0);
        packet.setSessionType(0);
        packet.setTotalAmount(50L);
        packet.setTotalCount(1);
        packet.setCreatedTime(new Date(1700000000000L));
        packetMapper.insert(packet);
        service.handleRedPacketExpiration(9L);
        service.handleRedPacketExpiration(9L);
        assertEquals(
                50L,
                jdbc.queryForObject(
                        "SELECT balance FROM user_balance WHERE user_id=1", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM balance_log", Integer.class));
        assertEquals(2, packetMapper.selectById(9L).getStatus());
    }
}
