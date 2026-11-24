package cn.qingliao.aiservice.knowledge;

import cn.qingliao.aiservice.rag.retriever.HybridContentRetriever;
import cn.qingliao.aiservice.rag.retriever.RerankingContentRetriever;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScopedKnowledge {
    private final KnowledgeAccess access;
    private final KnowledgeRepository repo;
    private final EmbeddingModel embeddings;
    private final ScoringModel scoring;

    @Value("${rag.recall-top-n:20}")
    private int topN = 20;

    @Value("${rag.rerank-top-k:5}")
    private int topK = 5;

    @Value("${rag.rerank-min-score:0.2}")
    private double minimum = 0.2;

    public ContentRetriever retriever(long user, long session) {
        return query -> retrieve(user, session, query.text());
    }

    public List<Content> retrieve(long user, long session, String question) {
        if (question == null || question.isBlank() || question.length() > 4000)
            throw KnowledgeAccess.invalid("问题不能为空且最多4000字符");
        var context = access.context(user, session);
        ContentRetriever vector =
                q -> {
                    try {
                        return repo.vectors(
                                context, embeddings.embed(q.text()).content().vector(), topN);
                    } catch (Exception error) {
                        log.warn("知识向量召回不可用，继续限定范围的关键词查询");
                        return List.of();
                    }
                };
        ContentRetriever keyword = q -> repo.keywords(context, q.text(), topN);
        var hybrid = new HybridContentRetriever(vector, keyword, topN, 60);
        var hits =
                new RerankingContentRetriever(hybrid, scoring, topK, minimum)
                        .retrieve(Query.from(question));
        // 精排可能跨网络等待；返回前再次检查成员和文档状态。
        var latest = access.context(user, session);
        return hits.stream()
                .filter(
                        hit -> {
                            var d =
                                    repo.find(
                                            hit.textSegment().metadata().getString("document_id"));
                            return d != null
                                    && "READY".equals(d.status())
                                    && ("SESSION".equals(d.scope())
                                            ? d.sessionId() == latest.sessionId()
                                            : latest.personal() && d.ownerId() == latest.userId());
                        })
                .toList();
    }

    public String memoryKey(long user, long session) {
        var context = access.context(user, session);
        return "kb-v2:" + user + ":" + session + ":" + repo.memoryRevision(context);
    }

    public record Source(
            String documentId, String filename, int chunkNo, String scope, String excerpt) {}

    public List<Source> sources(List<Content> hits) {
        return hits.stream()
                .map(
                        hit -> {
                            var m = hit.textSegment().metadata();
                            return new Source(
                                    m.getString("document_id"),
                                    m.getString("file_name"),
                                    m.getInteger("chunk_no"),
                                    m.getString("scope"),
                                    hit.textSegment().text());
                        })
                .toList();
    }
}
