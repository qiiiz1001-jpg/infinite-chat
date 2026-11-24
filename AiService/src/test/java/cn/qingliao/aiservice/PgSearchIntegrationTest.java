package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.*;

import cn.qingliao.aiservice.rag.retriever.PgBm25ContentRetriever;

import dev.langchain4j.rag.query.Query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "RUN_INFRA_TESTS", matches = "true")
class PgSearchIntegrationTest {
    @Test
    void realParadeDbSearchFindsFixtureAndRemovesIt() {
        var source = new DriverManagerDataSource();
        source.setUrl(
                "jdbc:postgresql://localhost:"
                        + System.getenv().getOrDefault("PGVECTOR_PORT", "15432")
                        + "/chat_vectors");
        source.setUsername(System.getenv().getOrDefault("PGVECTOR_USER", "chat"));
        source.setPassword(System.getenv("PGVECTOR_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        UUID id = UUID.randomUUID();
        String word = "verify" + id.toString().replace("-", "");
        try {
            jdbc.update(
                    "INSERT INTO chat_embeddings(embedding_id,text,metadata) VALUES"
                            + " (?,?,'{}'::json)",
                    id,
                    word + " redis knowledge");
            var hits =
                    new PgBm25ContentRetriever(jdbc, "chat_embeddings", 10)
                            .retrieve(Query.from(word));
            assertTrue(hits.stream().anyMatch(hit -> hit.textSegment().text().contains(word)));
            assertEquals(
                    0.0,
                    jdbc.queryForObject(
                            "SELECT '[1,0]'::vector <=> '[1,0]'::vector", Double.class));
        } finally {
            jdbc.update("DELETE FROM chat_embeddings WHERE embedding_id=?", id);
        }
    }
}
