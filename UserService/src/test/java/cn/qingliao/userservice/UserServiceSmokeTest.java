package cn.qingliao.userservice;

import static org.junit.jupiter.api.Assertions.*;
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
import org.springframework.util.DigestUtils;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/** 完整 Servlet 应用、真实 HTTP 与 H2；Redis/Kafka 使用模拟对象避免依赖本机中间件。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:user-smoke;MODE=MySQL;NON_KEYWORDS=USER,SESSION;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-user-test.sql",
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.kafka.listener.auto-startup=false",
            "spring.kafka.admin.auto-create=false",
            "chat.scheduling.enabled=false"
        })
class UserServiceSmokeTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private cn.qingliao.userservice.controller.SessionAccessController access;

    @Autowired private cn.qingliao.userservice.consumer.FriendRequestExpirationExecutor expiration;

    @MockitoBean private StringRedisTemplate redis;
    @MockitoBean private KafkaTemplate<String, String> kafka;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        jdbc.update("DELETE FROM user_session");
        jdbc.update("DELETE FROM friend");
        jdbc.update("DELETE FROM apply_friend");
        jdbc.update("DELETE FROM session");
        jdbc.update("DELETE FROM user");
        String password = DigestUtils.md5DigestAsHex("goat123456".getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                "INSERT INTO user(user_id,email,password,nickname,state)"
                        + " VALUES(1001,'a@example.com',?,'A',0),(2002,'b@example.com',?,'B',0)",
                password,
                password);
        jdbc.update(
                "INSERT INTO friend(user_id,friend_id,status) VALUES(1001,2002,0),(2002,1001,0)");
        Map<String, String> tokens = new ConcurrentHashMap<>();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> tokens.get(call.getArgument(0)));
        doAnswer(
                        call -> {
                            tokens.put(call.getArgument(0), call.getArgument(1));
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void friendApplicationReadAcceptAndNotificationsFollowOriginalContract() throws Exception {
        jdbc.update("DELETE FROM friend");
        String sender = login("a@example.com");
        String receiver = login("b@example.com");
        assertEquals(
                200,
                post("/api/contact/1001/friend/2002", "{\"msg\":\"hello\"}", sender)
                        .path("code")
                        .asInt());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM apply_friend", Integer.class));
        String ids = "{\"receiveuserIds\":[\"1001\"]}";
        assertEquals(
                200, post("/api/contact/2002/application/3", ids, receiver).path("code").asInt());
        assertEquals(3, jdbc.queryForObject("SELECT status FROM apply_friend", Integer.class));
        JsonNode accepted = post("/api/contact/2002/application/1", ids, receiver);
        assertEquals(200, accepted.path("code").asInt());
        assertEquals("1001", accepted.path("data").path("userId").asText());
        assertEquals("A", accepted.path("data").path("sessionName").asText());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM apply_friend", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM friend", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_session", Integer.class));
        assertNotEquals(
                200, post("/api/contact/2002/application/1", ids, receiver).path("code").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session", Integer.class));
        var messages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2))
                .send(eq("system-notification-topic"), anyString(), messages.capture());
        JsonNode apply = json.readTree(messages.getAllValues().get(0));
        assertEquals(101, apply.path("type").asInt());
        assertEquals("2002", apply.path("receiverId").asText());
        assertEquals("A", apply.path("body").path("nickname").asText());
        assertEquals("hello", apply.path("body").path("msg").asText());
        JsonNode session = json.readTree(messages.getAllValues().get(1));
        assertEquals(102, session.path("type").asInt());
        assertEquals("1001", session.path("receiverId").asText());
        assertEquals("B", session.path("body").path("sessionName").asText());
    }

    @Test
    void rejectedApplicationReusesIdAndOldExpiryDoesNotExpireResubmission() throws Exception {
        jdbc.update("DELETE FROM friend");
        String sender = login("a@example.com");
        String receiver = login("b@example.com");
        assertEquals(
                200,
                post("/api/contact/1001/friend/2002", "{\"msg\":\"first\"}", sender)
                        .path("code")
                        .asInt());
        Long id = jdbc.queryForObject("SELECT apply_friend_id FROM apply_friend", Long.class);
        assertEquals(
                200,
                post("/api/contact/2002/application/2", "{\"receiveuserIds\":[\"1001\"]}", receiver)
                        .path("code")
                        .asInt());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM apply_friend", Integer.class));
        assertEquals(
                200,
                post("/api/contact/1001/friend/2002", "{\"msg\":\"again\"}", sender)
                        .path("code")
                        .asInt());
        assertEquals(
                id, jdbc.queryForObject("SELECT apply_friend_id FROM apply_friend", Long.class));
        assertEquals(
                "again", jdbc.queryForObject("SELECT message FROM apply_friend", String.class));
        expiration.executeExpiration("{\"applyFriendId\":\"" + id + "\",\"expireTime\":1}");
        assertEquals(0, jdbc.queryForObject("SELECT status FROM apply_friend", Integer.class));
        jdbc.update(
                "UPDATE apply_friend SET created_time=?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(25)));
        expiration.executeExpiration("{\"applyFriendId\":\"" + id + "\",\"expireTime\":1}");
        assertEquals(4, jdbc.queryForObject("SELECT status FROM apply_friend", Integer.class));
    }

    @Test
    void expiryRepairOnlyChangesOldUnreadAndReadApplications() {
        int[] states = {0, 3, 1, 2, 4, 0};
        for (int i = 0; i < states.length; i++) {
            var time =
                    java.sql.Timestamp.valueOf(
                            java.time.LocalDateTime.now().minusHours(i == 5 ? 1 : 25));
            jdbc.update(
                    "INSERT INTO"
                        + " apply_friend(apply_friend_id,sender_id,receiver_id,status,created_time,updated_time)"
                        + " VALUES(?,?,?,?,?,?)",
                    i + 1,
                    10000 + i,
                    2002,
                    states[i],
                    time,
                    time);
        }
        expiration.repairExpiredRequests();
        assertEquals(
                java.util.List.of(4, 4, 1, 2, 4, 0),
                jdbc.queryForList(
                        "SELECT status FROM apply_friend ORDER BY apply_friend_id", Integer.class));
        expiration.repairExpiredRequests();
        assertEquals(
                java.util.List.of(4, 4, 1, 2, 4, 0),
                jdbc.queryForList(
                        "SELECT status FROM apply_friend ORDER BY apply_friend_id", Integer.class));
    }

    @Test
    void blockRuleIsDirectionalAsInOriginalDocument() {
        createSingleSession();
        assertTrue(access.canSend(1001L, 99L, 0, 2002L));
        assertTrue(access.canSend(2002L, 99L, 0, 1001L));
        jdbc.update("UPDATE friend SET status=1 WHERE user_id=1001 AND friend_id=2002");
        assertTrue(access.canSend(1001L, 99L, 0, 2002L), "A 拉黑 B 后 A 仍能发给 B");
        assertFalse(access.canSend(2002L, 99L, 0, 1001L), "B 不能发给拉黑自己的 A");
        jdbc.update("UPDATE friend SET status=1 WHERE user_id=2002 AND friend_id=1001");
        assertFalse(access.canSend(1001L, 99L, 0, 2002L));
        assertFalse(access.canSend(2002L, 99L, 0, 1001L));
    }

    @Test
    void directionalBlockRuleStillRejectsDeletedRelationsAndInvalidMembership() {
        createSingleSession();
        jdbc.update("UPDATE friend SET status=2 WHERE user_id=1001 AND friend_id=2002");
        assertFalse(access.canSend(1001L, 99L, 0, 2002L));
        jdbc.update("UPDATE friend SET status=0");
        jdbc.update("DELETE FROM user_session WHERE user_id=2002");
        assertFalse(access.canSend(1001L, 99L, 0, 2002L));
        assertFalse(access.canSend(1001L, 99L, 1, null));
    }

    private void createSingleSession() {
        jdbc.update("INSERT INTO session(session_id,name,type,status) VALUES(99,'single',0,0)");
        jdbc.update(
                "INSERT INTO user_session(user_id,session_id,role,status)"
                        + " VALUES(1001,99,2,0),(2002,99,2,0)");
    }

    @Test
    void loginConsumesOfflineTimestampOnce() throws Exception {
        when(redis.opsForValue().getAndDelete("user:offline:1001"))
                .thenReturn("1700000000000", (String) null);
        JsonNode first =
                post(
                        "/api/user/login/password",
                        "{\"email\":\"a@example.com\",\"password\":\"123456\"}",
                        null);
        assertEquals(1700000000000L, first.path("data").path("offlineTime").asLong());
        JsonNode second =
                post(
                        "/api/user/login/password",
                        "{\"email\":\"a@example.com\",\"password\":\"123456\"}",
                        null);
        assertTrue(
                second.path("data").path("offlineTime").isNull()
                        || second.path("data").path("offlineTime").isMissingNode());
        verify(redis.opsForValue(), times(2)).getAndDelete("user:offline:1001");
    }

    @Test
    void passwordLoginCreatesGroupAndOwnerCanRemoveMember() throws Exception {
        seedGroupFriend();
        String token = login("a@example.com");
        JsonNode created =
                post("/api/group", "{\"creatorId\":1001,\"memberIds\":[2002,4004]}", token);
        assertEquals(200, created.path("code").asInt());
        String session = created.path("data").path("sessionId").asText();
        assertTrue(created.path("data").path("sessionId").isTextual(), "雪花 ID 应按字符串传输");
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_session WHERE session_id=?",
                        Integer.class,
                        Long.valueOf(session)));
        JsonNode kicked =
                post(
                        "/api/group/kick",
                        "{\"sessionId\":\""
                                + session
                                + "\",\"operatorId\":1001,\"memberIds\":[2002]}",
                        token);
        assertEquals(200, kicked.path("code").asInt());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_session WHERE session_id=?",
                        Integer.class,
                        Long.valueOf(session)));
    }

    @Test
    void memberCannotSpoofOwnerOrKickOwner() throws Exception {
        seedGroupFriend();
        String owner = login("a@example.com");
        String session =
                post("/api/group", "{\"creatorId\":1001,\"memberIds\":[2002,4004]}", owner)
                        .path("data")
                        .path("sessionId")
                        .asText();
        String member = login("b@example.com");
        JsonNode spoof =
                post(
                        "/api/group/kick",
                        "{\"sessionId\":\""
                                + session
                                + "\",\"operatorId\":1001,\"memberIds\":[2002]}",
                        member);
        assertEquals(40300, spoof.path("code").asInt());
        JsonNode denied =
                post(
                        "/api/group/kick",
                        "{\"sessionId\":\""
                                + session
                                + "\",\"operatorId\":2002,\"memberIds\":[1001]}",
                        member);
        assertEquals(40300, denied.path("code").asInt());
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_session WHERE session_id=?",
                        Integer.class,
                        Long.valueOf(session)));
    }

    @Test
    void createGroupReturnsOriginalFieldsAndNotificationContract() throws Exception {
        seedGroupFriend();
        seedUser(3003, "c@example.com", "C");
        String token = login("a@example.com");
        JsonNode result =
                post(
                        "/api/group",
                        "{\"creatorId\":1001,\"memberIds\":[2002,3003,4004,2002]}",
                        token);
        assertEquals(200, result.path("code").asInt());
        JsonNode group = result.path("data");
        assertEquals("A、B、D", group.path("sessionName").asText());
        assertEquals(1, group.path("sessionType").asInt());
        assertEquals("/assets/group-avatar.png", group.path("avatar").asText());
        assertEquals(3003, group.path("failedMemberIds").get(0).asLong());
        assertEquals(1, group.path("failedMemberIds").size());
        JsonNode event =
                events().stream()
                        .filter(e -> e.path("type").asInt() == 103)
                        .findFirst()
                        .orElseThrow();
        assertTrue(event.has("senderId") && event.get("senderId").isNull());
        assertEquals(1001, event.path("body").path("creatorId").asLong());
        assertEquals(3, event.path("body").path("membersCount").asInt());
        assertEquals(group.path("avatar").asText(), event.path("body").path("avatar").asText());
        assertEquals(
                group.path("avatar").asText(),
                jdbc.queryForObject(
                        "SELECT avatar FROM session WHERE session_id=?",
                        String.class,
                        group.path("sessionId").asLong()));
    }

    @Test
    void originalGroupNameIsTruncatedToSixteenCharacters() throws Exception {
        seedGroupFriend();
        jdbc.update("UPDATE user SET nickname='abcdefghijklmnopqrst' WHERE user_id=1001");
        JsonNode created =
                post("/api/group", "{\"memberIds\":[2002,4004]}", login("a@example.com"));
        assertEquals("abcdefghijklmnop", created.path("data").path("sessionName").asText());
    }

    @Test
    void inviteKickAndExitKeepOriginalNotificationBodies() throws Exception {
        seedGroupFriend();
        seedUser(3003, "c@example.com", "C");
        jdbc.update("INSERT INTO friend(user_id,friend_id,status) VALUES(1001,3003,0)");
        String owner = login("a@example.com");
        String session =
                post("/api/group", "{\"memberIds\":[2002,4004]}", owner)
                        .path("data")
                        .path("sessionId")
                        .asText();
        JsonNode invited =
                post(
                        "/api/group/invite",
                        "{\"sessionId\":\""
                                + session
                                + "\",\"inviterId\":1001,\"inviteeIds\":[3003]}",
                        owner);
        assertEquals(200, invited.path("code").asInt());
        JsonNode invitation =
                events().stream()
                        .filter(
                                e ->
                                        e.path("type").asInt() == 103
                                                && e.path("receiverId").asLong() == 3003)
                        .findFirst()
                        .orElseThrow();
        assertEquals(4, invitation.path("body").path("membersCount").asInt());
        assertEquals(1001, invitation.path("body").path("creatorId").asLong());
        assertFalse(invitation.path("body").path("avatar").asText().isBlank());
        post(
                "/api/group/kick",
                "{\"sessionId\":\"" + session + "\",\"operatorId\":1001,\"memberIds\":[2002]}",
                owner);
        JsonNode kicked =
                events().stream()
                        .filter(
                                e ->
                                        e.path("type").asInt() == 104
                                                && e.path("receiverId").asLong() == 2002)
                        .findFirst()
                        .orElseThrow();
        assertEquals(1001, kicked.path("body").path("operatorId").asLong());
        assertEquals(2002, kicked.path("body").path("memberIds").get(0).asLong());
        clearInvocations(kafka);
        JsonNode exited =
                post(
                        "/api/group/exit",
                        "{\"sessionId\":\"" + session + "\",\"userId\":3003}",
                        login("c@example.com"));
        assertEquals(200, exited.path("code").asInt());
        JsonNode event = events().get(0);
        assertEquals(1001, event.path("receiverId").asLong());
        assertEquals(3003, event.path("body").path("memberIds").get(0).asLong());
        assertTrue(
                event.path("body").has("operatorId")
                        && event.path("body").get("operatorId").isNull());
    }

    @Test
    void groupPaginationUsesJoinTimeAndReturnsCompleteMetadata() throws Exception {
        seedGroupFriend();
        String owner = login("a@example.com");
        String first =
                post("/api/group", "{\"memberIds\":[2002,4004]}", owner)
                        .path("data")
                        .path("sessionId")
                        .asText();
        String second =
                post("/api/group", "{\"memberIds\":[2002,4004]}", owner)
                        .path("data")
                        .path("sessionId")
                        .asText();
        jdbc.update(
                "UPDATE user_session SET created_time='2030-01-03 00:00:00' WHERE user_id=1001 AND"
                        + " session_id=?",
                Long.valueOf(first));
        jdbc.update(
                "UPDATE user_session SET created_time='2020-01-01 00:00:00' WHERE user_id=1001 AND"
                        + " session_id=?",
                Long.valueOf(second));
        JsonNode page = get("/api/group/user/1001?pageNum=1&pageSize=1", owner).path("data");
        assertEquals(first, page.path("list").get(0).path("sessionId").asText());
        assertEquals("A、B、D", page.path("list").get(0).path("name").asText());
        assertEquals(3, page.path("list").get(0).path("memberCount").asInt());
        assertEquals("2030-01-03 00:00:00", page.path("list").get(0).path("createdTime").asText());
        assertEquals(2, page.path("total").asInt());
        assertEquals(1, page.path("pageNum").asInt());
        assertEquals(1, page.path("pageSize").asInt());
        assertEquals(2, page.path("pages").asInt());
        assertTrue(page.path("hasNext").asBoolean());
        assertFalse(page.path("hasPrevious").asBoolean());
        JsonNode memberPage =
                get("/api/group/" + first + "/members?pageNum=2&pageSize=1", owner).path("data");
        assertEquals(2, memberPage.path("pageNum").asInt());
        assertEquals(3, memberPage.path("pages").asInt());
        assertTrue(memberPage.path("hasPrevious").asBoolean());
        assertTrue(memberPage.path("hasNext").asBoolean());
        seedUser(3003, "c@example.com", "C");
        JsonNode empty =
                get("/api/group/user/3003?pageNum=1&pageSize=10", login("c@example.com"))
                        .path("data");
        assertEquals(10, empty.path("pageSize").asInt());
        assertEquals(0, empty.path("pages").asInt());
        assertTrue(empty.path("list").isEmpty());
    }

    @Test
    void committedGroupReportsNotificationFailureWithoutRollingBackMembership() throws Exception {
        seedGroupFriend();
        when(kafka.send(eq("system-notification-topic"), eq("2002"), anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("test send failure")));
        JsonNode response =
                post("/api/group", "{\"memberIds\":[2002,4004]}", login("a@example.com"));
        assertEquals(200, response.path("code").asInt());
        assertEquals(2002, response.path("data").path("failedMemberIds").get(0).asLong());
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_session WHERE session_id=?",
                        Integer.class,
                        response.path("data").path("sessionId").asLong()));
    }

    @Test
    void groupCreationRequiresTwoDistinctValidFriends() throws Exception {
        String owner = login("a@example.com");
        JsonNode single = post("/api/group", "{\"memberIds\":[2002]}", owner);
        JsonNode duplicate = post("/api/group", "{\"memberIds\":[2002,2002]}", owner);
        assertEquals(40000, single.path("code").asInt());
        assertEquals(40000, duplicate.path("code").asInt());
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM session WHERE type=1", Integer.class));
        seedGroupFriend();
        JsonNode named =
                post("/api/group", "{\"name\":\"产品评审小组\",\"memberIds\":[2002,4004]}", owner);
        assertEquals(200, named.path("code").asInt());
        assertEquals("产品评审小组", named.path("data").path("sessionName").asText());
        assertEquals(3, named.path("data").path("memberIds").size());
    }

    private void seedGroupFriend() {
        seedUser(4004, "d@example.com", "D");
        jdbc.update("INSERT INTO friend(user_id,friend_id,status) VALUES(1001,4004,0)");
    }

    private void seedUser(long id, String email, String name) {
        String password = DigestUtils.md5DigestAsHex("goat123456".getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                "INSERT INTO user(user_id,email,password,nickname,state) VALUES(?,?,?,?,0)",
                id,
                email,
                password,
                name);
    }

    private java.util.List<JsonNode> events() throws Exception {
        var payloads = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka, atLeastOnce())
                .send(eq("system-notification-topic"), anyString(), payloads.capture());
        java.util.List<JsonNode> result = new java.util.ArrayList<>();
        for (String payload : payloads.getAllValues()) result.add(json.readTree(payload));
        return result;
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Access-Token", token)
                        .GET()
                        .build();
        return json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void parameterValidationKeepsOriginalErrorContract() throws Exception {
        JsonNode missing = get("/api/user/uploadUrl", login("a@example.com"));
        assertEquals(50003, missing.path("code").asInt());
        assertEquals("缺少必填参数", missing.path("message").asText());
        JsonNode invalid =
                post("/api/user/login/password", "{\"email\":\"bad\",\"password\":\"\"}", null);
        assertEquals(50003, invalid.path("code").asInt());
    }

    private String login(String email) throws Exception {
        JsonNode response =
                post(
                        "/api/user/login/password",
                        "{\"email\":\"" + email + "\",\"password\":\"123456\"}",
                        null);
        assertEquals(200, response.path("code").asInt());
        return response.path("data").path("accessToken").asText();
    }

    private JsonNode post(String path, String body, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Access-Token", token);
        return json.readTree(
                client.send(request.build(), HttpResponse.BodyHandlers.ofString()).body());
    }
}
