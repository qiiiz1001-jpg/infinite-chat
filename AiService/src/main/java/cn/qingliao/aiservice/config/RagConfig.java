package cn.qingliao.aiservice.config;

import cn.qingliao.aiservice.rag.retriever.HybridContentRetriever;
import cn.qingliao.aiservice.rag.retriever.PgBm25ContentRetriever;
import cn.qingliao.aiservice.rag.retriever.RerankingContentRetriever;
import cn.qingliao.aiservice.rag.splitter.MarkdownHeadingSplitter;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@SuppressWarnings({"all"})
public class RagConfig {

    @Resource private EmbeddingModel embeddingModel;

    @Resource private EmbeddingStore<TextSegment> embeddingStore;

    @Value("${rag.docs-path}")
    private String docsPath;

    @Value("${pgvector.table}")
    private String table;

    @Value("${rag.recall-top-n:20}")
    private int recallTopN;

    @Value("${rag.rerank-top-k:5}")
    private int rerankTopK;

    @Value("${rag.rerank-min-score:0.2}")
    private double rerankMinScore;

    /** 入库流水线：切分、补来源、向量化，再保存片段。 */
    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor() {
        DocumentSplitter subSplitter = DocumentSplitters.recursive(1000, 100);
        DocumentSplitter splitter = new MarkdownHeadingSplitter(3, 1500, subSplitter);
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .textSegmentTransformer(
                        textSegment ->
                                TextSegment.from(
                                        textSegment.metadata().getString("file_name")
                                                + "\n"
                                                + textSegment.text(),
                                        textSegment.metadata()))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    /** 语义召回一路，取最多 recallTopN 条且受向量分阈值限制。 */
    @Bean
    public ContentRetriever vectorContentRetriever() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(recallTopN)
                .minScore(0.75)
                .build();
    }

    /** 关键词召回一路，与向量检索读取同一张知识表。 */
    @Bean
    public PgBm25ContentRetriever bm25ContentRetriever(JdbcTemplate jdbcTemplate) {
        return new PgBm25ContentRetriever(jdbcTemplate, table, recallTopN);
    }

    /** 将两路候选按排名融合，供后续重排使用。 */
    @Bean
    public ContentRetriever hybridContentRetriever(
            @Qualifier("vectorContentRetriever") ContentRetriever vectorContentRetriever,
            PgBm25ContentRetriever bm25ContentRetriever) {
        return new HybridContentRetriever(
                vectorContentRetriever, bm25ContentRetriever, recallTopN, 60);
    }

    /** 最终检索出口：助手、查重工具与评测端共用。 */
    @Bean
    public ContentRetriever rerankingContentRetriever(
            @Qualifier("hybridContentRetriever") ContentRetriever hybridContentRetriever,
            ScoringModel scoringModel) {
        return new RerankingContentRetriever(
                hybridContentRetriever, scoringModel, rerankTopK, rerankMinScore);
    }
}
