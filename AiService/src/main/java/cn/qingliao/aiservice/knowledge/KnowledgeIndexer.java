package cn.qingliao.aiservice.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "rag.indexing.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexer {
    private final KnowledgeRepository repo;
    private final EmbeddingModel embeddings;

    @Scheduled(fixedDelayString = "${rag.indexing.scan-ms:3000}")
    public void tick() {
        try {
            repo.recover();
            var doc = repo.claim();
            if (doc != null) index(doc);
        } catch (Exception error) {
            log.error("知识索引调度失败，请检查PostgreSQL知识库迁移", error);
        }
    }

    public void index(KnowledgeRepository.Doc doc) {
        try {
            var segments =
                    DocumentSplitters.recursive(1200, 150).split(Document.from(doc.parsedText()));
            if (segments.isEmpty() || segments.size() > 256)
                throw new IllegalArgumentException("资料片段数超限");
            List<float[]> vectors = new ArrayList<>();
            for (int start = 0; start < segments.size(); start += 16) {
                repo.heartbeat(doc);
                var batch = segments.subList(start, Math.min(start + 16, segments.size()));
                var result = embeddings.embedAll(batch).content();
                if (result == null || result.size() != batch.size())
                    throw new IllegalStateException("Embedding返回数量不一致");
                for (var embedding : result) {
                    KnowledgeRepository.vector(embedding.vector());
                    vectors.add(embedding.vector());
                }
            }
            repo.complete(doc, segments.stream().map(TextSegment::text).toList(), vectors);
        } catch (Exception error) {
            log.warn("知识索引失败，documentId={}", doc.documentId(), error);
            repo.fail(doc);
        }
    }
}
