package cn.qingliao.userservice.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/** 真实登录、HTTP鉴权、MyBatis成员校验和实际迁移表；只模拟中间件。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:agent-http;MODE=MySQL;NON_KEYWORDS=USER,SESSION;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-user-test.sql,classpath:05-agent-actions.sql",
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.kafka.listener.auto-startup=false",
            "spring.kafka.admin.auto-create=false",
            "chat.scheduling.enabled=false",
            "chat.internal-token=agent-test-internal"
        })
class AgentActionHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean KafkaTemplate<String, String> kafka;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        jdbc.update("DELETE FROM agent_action");
        jdbc.update("DELETE FROM user_session");
        jdbc.update("DELETE FROM session");
        jdbc.update("DELETE FROM friend");
        jdbc.update("DELETE FROM user");
        String password =
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (cn.qingliao.userservice.constant.UserConstant.PASSWORD_SALT + "goat123456")
                                .getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                "INSERT INTO user(user_id,email,password,nickname,state) VALUES"
                        + " (1001,'a@example.com',?,'A',0),(2002,'b@example.com',?,'B',0)",
                password,
                password);
        jdbc.update(
                "INSERT INTO session(session_id,name,type,status)"
                        + " VALUES(10,'项目群',1,0),(11,'私密群',1,0)");
        jdbc.update(
                "INSERT INTO user_session(user_id,session_id,status)"
                        + " VALUES(1001,10,0),(2002,11,0)");
        Map<String, String> tokens = new ConcurrentHashMap<>();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c -> tokens.get(c.getArgument(0)));
        doAnswer(
                        c -> {
                            tokens.put(c.getArgument(0), c.getArgument(1));
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    JsonNode request(String path, String method, Object body, String token) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json");
        if (token != null) builder.header("Access-Token", token);
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return json.readTree(
                http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    String login(String email) throws Exception {
        return request(
                        "/api/user/login/password",
                        "POST",
                        Map.of("email", email, "password", "goat123456"),
                        null)
                .path("data")
                .path("accessToken")
                .asText();
    }

    @Test
    void knowledgeScopeRequiresActiveMembershipAndRecognizesOnlyOwnedAssistant() throws Exception {
        jdbc.update("INSERT INTO session(session_id,name,type,status) VALUES(20,'助手',2,0)");
        jdbc.update(
                "INSERT INTO user_session(user_id,session_id,status)"
                        + " VALUES(1001,20,0),(111111111,20,0)");
        java.util.function.BiFunction<Long, Long, JsonNode> check =
                (user, session) -> {
                    try {
                        var response =
                                http.send(
                                        HttpRequest.newBuilder(
                                                        URI.create(
                                                                "http://localhost:"
                                                                        + port
                                                                        + "/api/user/internal/knowledge-access?userId="
                                                                        + user
                                                                        + "&sessionId="
                                                                        + session))
                                                .header("X-Internal-Token", "agent-test-internal")
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString());
                        return json.readTree(response.body());
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                };
        assertTrue(check.apply(1001L, 20L).path("assistant").asBoolean());
        assertFalse(check.apply(2002L, 20L).path("accessible").asBoolean());
        assertTrue(check.apply(1001L, 10L).path("accessible").asBoolean());
        assertFalse(check.apply(1001L, 10L).path("assistant").asBoolean());
        jdbc.update("UPDATE user_session SET status=1 WHERE user_id=1001 AND session_id=20");
        assertFalse(check.apply(1001L, 20L).path("accessible").asBoolean());
        jdbc.update("UPDATE session SET status=1 WHERE session_id=10");
        assertFalse(check.apply(1001L, 10L).path("accessible").asBoolean());
        jdbc.update("UPDATE user SET state=1 WHERE user_id=2002");
        assertFalse(check.apply(2002L, 11L).path("accessible").asBoolean());
    }

    @Test
    void loginOwnershipAndHumanConfirmationCannotBeBypassed() throws Exception {
        String alice = login("a@example.com"), bob = login("b@example.com");
        assertFalse(alice.isBlank());
        assertNotEquals(
                200, request("/api/user/agent/actions", "GET", null, null).path("code").asInt());
        var sessions = request("/api/user/agent/sessions", "GET", null, alice).path("data");
        assertEquals(1, sessions.size());
        assertEquals("项目群", sessions.get(0).path("name").asText());
        Map<String, Object> body =
                Map.of(
                        "requestKey",
                        "http-one",
                        "kind",
                        "MESSAGE",
                        "sessionId",
                        "10",
                        "content",
                        "大家好");
        JsonNode action = request("/api/user/agent/actions", "POST", body, alice);
        assertEquals(200, action.path("code").asInt());
        String id = action.path("data").path("actionId").asText();
        assertEquals("DRAFT", action.path("data").path("status").asText());
        verifyNoInteractions(kafka);
        assertNotEquals(
                200,
                request(
                                "/api/user/agent/actions/" + id + "/confirm",
                                "POST",
                                Map.of("version", 0),
                                bob)
                        .path("code")
                        .asInt());
        assertNotEquals(
                200,
                request(
                                "/api/user/agent/actions/" + id,
                                "PATCH",
                                Map.of("version", 0, "content", "篡改"),
                                bob)
                        .path("code")
                        .asInt());
        assertNotEquals(
                200,
                request("/api/user/internal/agent/actions?actorId=1001", "GET", null, alice)
                        .path("code")
                        .asInt());
        assertEquals(
                "PENDING",
                request(
                                "/api/user/agent/actions/" + id + "/confirm",
                                "POST",
                                Map.of("version", 0),
                                alice)
                        .path("data")
                        .path("status")
                        .asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_action", Integer.class));
        assertEquals(
                "CANCELLED",
                request(
                                "/api/user/agent/actions/" + id + "/cancel",
                                "POST",
                                Map.of("version", 0),
                                alice)
                        .path("data")
                        .path("status")
                        .asText());
    }

    @Test
    void foreignSessionsAndChangedMembershipAreRejected() throws Exception {
        String alice = login("a@example.com");
        assertNotEquals(
                200,
                request(
                                "/api/user/agent/actions",
                                "POST",
                                Map.of(
                                        "requestKey",
                                        "foreign",
                                        "kind",
                                        "MESSAGE",
                                        "sessionId",
                                        "11",
                                        "content",
                                        "x"),
                                alice)
                        .path("code")
                        .asInt());
        var a =
                request(
                                "/api/user/agent/actions",
                                "POST",
                                Map.of(
                                        "requestKey",
                                        "leave",
                                        "kind",
                                        "MESSAGE",
                                        "sessionId",
                                        "10",
                                        "content",
                                        "x"),
                                alice)
                        .path("data");
        jdbc.update("DELETE FROM user_session WHERE user_id=1001");
        assertNotEquals(
                200,
                request(
                                "/api/user/agent/actions/"
                                        + a.path("actionId").asText()
                                        + "/confirm",
                                "POST",
                                Map.of("version", 0),
                                alice)
                        .path("code")
                        .asInt());
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM agent_action", String.class));
    }
}
