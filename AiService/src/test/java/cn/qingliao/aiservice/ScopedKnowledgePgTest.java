package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.qingliao.aiservice.client.SessionAccessClient;
import cn.qingliao.aiservice.knowledge.*;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.KnowledgeSession;
import cn.qingliao.common.utils.JwtUtil;

import com.fasterxml.jackson.databind.*;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 真PostgreSQL/pgvector/pg_search和本机HTTP；仅在独立随机schema执行并清理。模型与身份RPC用替身。 */
@EnabledIfEnvironmentVariable(named = "RUN_KNOWLEDGE_PG_TESTS", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "spring.cloud.nacos.discovery.enabled=false",
            "mcp.enabled=false",
            "rag.load-on-startup=false",
            "rag.indexing.enabled=false",
            "langchain4j.community.dashscope.chat-model.api-key=test-key",
            "langchain4j.community.dashscope.streaming-chat-model.api-key=test-key",
            "langchain4j.community.dashscope.embedding-model.api-key=test-key"
        })
class ScopedKnowledgePgTest {
    static final String SCHEMA = "kb_test_" + UUID.randomUUID().toString().replace("-", "");
    static DriverManagerDataSource admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String host = System.getenv().getOrDefault("PGVECTOR_HOST", "localhost"),
                port = System.getenv().getOrDefault("PGVECTOR_PORT", "15432");
        String db = System.getenv().getOrDefault("PGVECTOR_DATABASE", "chat_vectors"),
                user = System.getenv().getOrDefault("PGVECTOR_USER", "chat");
        String password = System.getenv("PGVECTOR_PASSWORD");
        if (password == null)
            throw new IllegalStateException(
                    "Load project .env before opting into PostgreSQL tests");
        admin =
                new DriverManagerDataSource(
                        "jdbc:postgresql://" + host + ":" + port + "/" + db, user, password);
        var sql = new JdbcTemplate(admin);
        sql.execute("CREATE SCHEMA " + SCHEMA);
        try (var connection = admin.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA + ",public");
            statement.execute(
                    new ClassPathResource("03-scoped-knowledge.sql")
                            .getContentAsString(StandardCharsets.UTF_8));
        }
        properties.add("pgvector.host", () -> host);
        properties.add("pgvector.port", () -> port);
        properties.add("pgvector.database", () -> db);
        properties.add("pgvector.user", () -> user);
        properties.add("pgvector.password", () -> password);
        properties.add("pgvector.schema", () -> SCHEMA);
    }

    @AfterAll
    static void cleanup() {
        if (admin != null)
            new JdbcTemplate(admin).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @LocalServerPort int port;
    @Autowired KnowledgeRepository repo;
    @Autowired KnowledgeService service;
    @Autowired ScopedKnowledge knowledge;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmbeddingStore<TextSegment> embeddingStore;
    @MockitoBean RedisChatMemoryStore memory;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean SessionAccessClient sessions;
    @MockitoBean EmbeddingModel embeddings;
    @MockitoBean ScoringModel scoring;
    @MockitoBean ChatModel chatModel;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final Map<Long, String> tokens = new HashMap<>();
    final Set<String> memberships = new HashSet<>();
    final String term = "projectuniqueterm";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.update("DELETE FROM kb_chunk");
        jdbc.update("DELETE FROM kb_document");
        memberships.clear();
        Collections.addAll(memberships, "1:10", "2:10", "2:11", "1:20", "2:21");
        when(sessions.knowledgeAccess(anyLong(), anyLong()))
                .thenAnswer(
                        call -> {
                            long u = call.getArgument(0), s = call.getArgument(1);
                            return new KnowledgeSession(
                                    memberships.contains(u + ":" + s),
                                    (u == 1 && s == 20) || (u == 2 && s == 21));
                        });
        var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        tokens.clear();
        for (long u : List.of(1L, 2L))
            tokens.put(u, JwtUtil.generate(Long.toString(u), 1, TimeUnit.HOURS));
        when(values.get(anyString()))
                .thenAnswer(
                        c -> {
                            String k = c.getArgument(0);
                            for (var entry : tokens.entrySet())
                                if (k.equals(CommonConstant.ACCESS_TOKEN_PREFIX + entry.getKey()))
                                    return entry.getValue();
                            return null;
                        });
        when(embeddings.embed(anyString())).thenReturn(Response.from(Embedding.from(vector())));
        doAnswer(
                        c ->
                                Response.from(
                                        ((List<?>) c.getArgument(0))
                                                .stream()
                                                        .map(s -> Embedding.from(vector()))
                                                        .toList()))
                .when(embeddings)
                .embedAll(anyList());
        when(scoring.scoreAll(anyList(), anyString()))
                .thenAnswer(
                        c ->
                                Response.from(
                                        Collections.nCopies(
                                                ((List<?>) c.getArgument(0)).size(), 0.9)));
        when(chatModel.chat(anyString())).thenReturn("依据 api.md 的片段1回答。");
    }

    static float[] vector() {
        float[] v = new float[1024];
        v[0] = 1;
        return v;
    }

    KnowledgeRepository.Doc create(long owner, String scope, long session, String content) {
        String sha = UUID.randomUUID().toString().replace("-", "");
        return repo.create(
                owner,
                scope,
                session,
                new KnowledgeParser.Parsed("api.md", content.length(), sha, content));
    }

    KnowledgeRepository.Doc ready(long owner, String scope, long session, String content) {
        var doc = create(owner, scope, session, content);
        var claimed = repo.claim();
        assertEquals(doc.documentId(), claimed.documentId());
        new KnowledgeIndexer(repo, embeddings).index(claimed);
        assertEquals("READY", repo.find(doc.documentId()).status());
        return repo.find(doc.documentId());
    }

    JsonNode request(String path, String method, Object body, Long user) throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json");
        if (user != null) b.header("Access-Token", tokens.get(user));
        b.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return json.readTree(http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    JsonNode upload(String scope, long session, String text, long user) throws Exception {
        String boundary = "kb-boundary",
                body =
                        "--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"api.md\"\r\n"
                                + "Content-Type: text/markdown\r\n\r\n"
                                + text
                                + "\r\n--"
                                + boundary
                                + "--\r\n";
        var req =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/ai/knowledge/documents?scope="
                                                + scope
                                                + (session == 0 ? "" : "&sessionId=" + session)))
                        .header("Access-Token", tokens.get(user))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void migrationAndBothQueriesFilterBeforeTopK() {
        var own = ready(1, "PERSONAL", 0, term + " private-one");
        ready(2, "PERSONAL", 0, term + " secret-two");
        var group = ready(1, "SESSION", 10, term + " team-ten");
        for (int i = 0; i < 8; i++) ready(2, "SESSION", 11, term + " foreign-" + i);
        var personal = new KnowledgeAccess.Context(1, 20, true);
        assertEquals(
                own.documentId(),
                repo.vectors(personal, vector(), 1)
                        .get(0)
                        .textSegment()
                        .metadata()
                        .getString("document_id"));
        assertEquals(
                own.documentId(),
                repo.keywords(personal, term, 1)
                        .get(0)
                        .textSegment()
                        .metadata()
                        .getString("document_id"));
        var team = new KnowledgeAccess.Context(1, 10, false);
        assertEquals(
                group.documentId(),
                repo.vectors(team, vector(), 1)
                        .get(0)
                        .textSegment()
                        .metadata()
                        .getString("document_id"));
        assertEquals(
                group.documentId(),
                repo.keywords(team, term, 1)
                        .get(0)
                        .textSegment()
                        .metadata()
                        .getString("document_id"));
        assertTrue(
                knowledge.retrieve(1, 10, term).stream()
                        .noneMatch(c -> c.textSegment().text().contains("private-one")));
        // 直接执行带权限和score的BM25 JOIN，不能让子串降级掩盖实际SQL问题。
        assertEquals(
                group.documentId(),
                jdbc.queryForObject(
                        "SELECT d.document_id FROM kb_chunk c JOIN kb_document d ON"
                            + " d.document_id=c.document_id WHERE d.status='READY' AND"
                            + " ((d.scope='SESSION' AND d.session_id=?) OR (d.scope='PERSONAL' AND"
                            + " d.owner_id=? AND ?)) AND c.chunk_id @@@ paradedb.match('content',?)"
                            + " ORDER BY paradedb.score(c.chunk_id) DESC,c.chunk_id LIMIT 1",
                        String.class,
                        10L,
                        1L,
                        false,
                        term));
        assertTrue(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname=? AND"
                                        + " indexname='kb_chunk_bm25'",
                                Integer.class,
                                SCHEMA)
                        > 0);
    }

    @Test
    void uploadListPreviewOwnerRulesAndHumanScopeAreEnforcedOverHttp() throws Exception {
        assertNotEquals(
                200,
                request("/api/ai/knowledge/documents?scope=PERSONAL", "GET", null, null)
                        .path("code")
                        .asInt());
        var response = upload("PERSONAL", 0, "我的私有资料", 1);
        assertEquals(200, response.path("code").asInt());
        String id = response.path("data").path("documentId").asText();
        assertEquals("PENDING", repo.find(id).status());
        assertEquals("1", response.path("data").path("ownerId").asText());
        assertEquals(
                id, upload("PERSONAL", 0, "我的私有资料", 1).path("data").path("documentId").asText());
        assertNotEquals(
                200,
                request("/api/ai/knowledge/documents/" + id + "/text", "GET", null, 2L)
                        .path("code")
                        .asInt());
        assertEquals(
                0,
                request("/api/ai/knowledge/documents?scope=PERSONAL", "GET", null, 2L)
                        .path("data")
                        .size());
        assertNotEquals(200, upload("SESSION", 11, "unauthorized", 1).path("code").asInt());
        assertNotEquals(200, upload("PERSONAL", 10, "wrong scope", 1).path("code").asInt());
        var shared = upload("SESSION", 10, "团队共享资料", 1);
        String sharedId = shared.path("data").path("documentId").asText();
        assertEquals(
                200,
                request("/api/ai/knowledge/documents/" + sharedId + "/text", "GET", null, 2L)
                        .path("code")
                        .asInt());
        assertNotEquals(
                200,
                request("/api/ai/knowledge/documents/" + sharedId, "DELETE", null, 2L)
                        .path("code")
                        .asInt());
        memberships.remove("2:10");
        assertNotEquals(
                200,
                request("/api/ai/knowledge/documents/" + sharedId + "/text", "GET", null, 2L)
                        .path("code")
                        .asInt());
        assertEquals(
                200,
                request("/api/ai/knowledge/documents/" + sharedId, "DELETE", null, 1L)
                        .path("code")
                        .asInt());
    }

    @Test
    void indexedAskReturnsRealSourcesAndDeletionChangesMemoryAndSearch() throws Exception {
        var doc = ready(1, "PERSONAL", 0, term + " login requires email");
        String old = knowledge.memoryKey(1, 20);
        var result =
                request(
                        "/api/ai/knowledge/ask",
                        "POST",
                        Map.of("sessionId", "20", "question", term),
                        1L);
        assertEquals(200, result.path("code").asInt());
        assertEquals(
                doc.documentId(),
                result.path("data").path("sources").get(0).path("documentId").asText());
        verify(chatModel).chat(contains("login requires email"));
        assertTrue(
                request(
                                "/api/ai/knowledge/search",
                                "POST",
                                Map.of("sessionId", "10", "question", term),
                                1L)
                        .path("data")
                        .isEmpty());
        service.delete(1, doc.documentId());
        assertNotEquals(old, knowledge.memoryKey(1, 20));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk", Integer.class));
        assertTrue(knowledge.retrieve(1, 20, term).isEmpty());
        assertNotEquals(
                200,
                request("/api/ai/insert", "POST", Map.of("question", "q", "answer", "a"), 1L)
                        .path("code")
                        .asInt());
    }

    @Test
    void failedEmbeddingsAreInvisibleAndCanBeRetried() {
        var doc = create(1, "PERSONAL", 0, term + " retry");
        when(embeddings.embedAll(anyList())).thenThrow(new IllegalStateException("offline"));
        new KnowledgeIndexer(repo, embeddings).index(repo.claim());
        assertEquals("FAILED", repo.find(doc.documentId()).status());
        assertTrue(knowledge.retrieve(1, 20, term).isEmpty());
        service.retry(1, doc.documentId());
        doAnswer(
                        c ->
                                Response.from(
                                        ((List<?>) c.getArgument(0))
                                                .stream()
                                                        .map(s -> Embedding.from(vector()))
                                                        .toList()))
                .when(embeddings)
                .embedAll(anyList());
        new KnowledgeIndexer(repo, embeddings).index(repo.claim());
        assertEquals("READY", repo.find(doc.documentId()).status());
    }

    @Test
    void deletedIndexLeaseCannotResurrectDocumentAndExpiredLeaseRecovers() {
        var first = create(1, "PERSONAL", 0, term);
        var stale = repo.claim();
        service.delete(1, first.documentId());
        repo.complete(stale, List.of(term), List.of(vector()));
        assertEquals("DELETED", repo.find(first.documentId()).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk", Integer.class));
        var second = create(1, "PERSONAL", 0, "next");
        repo.claim();
        jdbc.update(
                "UPDATE kb_document SET lease_until=0 WHERE document_id=?", second.documentId());
        repo.recover();
        assertEquals("PENDING", repo.find(second.documentId()).status());
    }

    @Test
    void competingIndexersOnlyOneClaimsAndVectorFailureRollsBack() throws Exception {
        var doc = create(1, "PERSONAL", 0, term);
        var pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<KnowledgeRepository.Doc>> jobs = new ArrayList<>();
            for (int i = 0; i < 6; i++) jobs.add(pool.submit(repo::claim));
            int claims = 0;
            for (var j : jobs) if (j.get() != null) claims++;
            assertEquals(1, claims);
        } finally {
            pool.shutdownNow();
        }
        var claimed = repo.find(doc.documentId());
        assertThrows(
                RuntimeException.class,
                () ->
                        repo.complete(
                                claimed,
                                List.of("first", "second"),
                                List.of(vector(), new float[2])));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk", Integer.class));
        assertEquals("INDEXING", repo.find(doc.documentId()).status());
    }
}
