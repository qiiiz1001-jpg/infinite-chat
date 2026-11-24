package cn.qingliao.aiservice.rag.retriever;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

@Slf4j
public class PgBm25ContentRetriever implements ContentRetriever {

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final int maxResults;

    public PgBm25ContentRetriever(JdbcTemplate jdbcTemplate, String table, int maxResults) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
        this.maxResults = maxResults;
    }

    /** 用 BM25 查询同表正文，异常降级为空，只兜底这一条召回路。 */
    @Override
    public List<Content> retrieve(Query query) {
        String question = query.text();
        if (question == null || question.isBlank()) {
            return Collections.emptyList();
        }

        String sql =
                "SELECT text, paradedb.score(embedding_id) AS score "
                        + "FROM "
                        + table
                        + " "
                        + "WHERE embedding_id @@@ paradedb.match('text', ?) "
                        + "ORDER BY score DESC "
                        + "LIMIT ?";

        try {
            List<Content> contents =
                    jdbcTemplate.query(
                            sql,
                            (rs, rowNum) -> Content.from(rs.getString("text")),
                            question,
                            maxResults);
            log.info("BM25 检索 - Q: {} | 命中 {} 条", question, contents.size());
            return contents;
        } catch (Exception e) {
            log.error("BM25 检索失败(降级为空结果): {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
