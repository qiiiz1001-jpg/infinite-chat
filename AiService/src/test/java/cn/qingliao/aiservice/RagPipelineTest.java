package cn.qingliao.aiservice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.qingliao.aiservice.rag.retriever.*;
import cn.qingliao.aiservice.rag.splitter.MarkdownHeadingSplitter;
import cn.qingliao.aiservice.skill.SkillTool;
import cn.qingliao.aiservice.tool.RagTool;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class RagPipelineTest {
    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        cn.qingliao.aiservice.Monitor.MonitorContextHolder.clearContext();
    }

    @TempDir Path temp;
    private final Query query = Query.from("轻聊");
    private final ScoringModel scoring = mock(ScoringModel.class);
    private final List<Content> hits =
            List.of(Content.from("A"), Content.from("B"), Content.from("C"));

    @Test
    void headingPreservesSourceAndDeeperHeadings() {
        var splitter = new MarkdownHeadingSplitter(3, 0, null);
        var parts =
                splitter.split(
                        Document.from(
                                "前言\n### Q. 一\nA：甲\n#### 注释\n说明\n### Q. 二\nA：乙",
                                Metadata.from("file_name", "Test.md")));
        assertThat(parts).hasSize(3);
        assertThat(parts.get(1).text()).contains("#### 注释", "A：甲");
        assertThat(parts)
                .allSatisfy(
                        p -> assertThat(p.metadata().getString("file_name")).isEqualTo("Test.md"));
    }

    @Test
    void longHeadingUsesFallbackWithoutLosingMetadata() {
        var splitter = new MarkdownHeadingSplitter(3, 30, DocumentSplitters.recursive(30, 5));
        var parts =
                splitter.split(
                        Document.from(
                                "### Q\n" + "知识片段。".repeat(40),
                                Metadata.from("file_name", "Test.md")));
        assertThat(parts.size()).isGreaterThan(1);
        assertThat(parts)
                .allSatisfy(
                        p -> assertThat(p.metadata().getString("file_name")).isEqualTo("Test.md"));
    }

    @Test
    void blankDocumentProducesNoSegments() {
        Document blank = mock(Document.class);
        when(blank.text()).thenReturn(" \n ");
        assertThat(new MarkdownHeadingSplitter(3, 0, null).split(blank)).isEmpty();
    }

    @Test
    void rrfFusesRanksAndDeduplicatesAcrossRetrievers() {
        var hybrid =
                new HybridContentRetriever(
                        q -> List.of(hits.get(0), hits.get(1)),
                        q -> List.of(hits.get(1), hits.get(2)),
                        3,
                        60);
        assertThat(hybrid.retrieve(query))
                .extracting(c -> c.textSegment().text())
                .containsExactly("B", "A", "C");
    }

    @Test
    void rrfCapsResults() {
        assertThat(new HybridContentRetriever(q -> hits, q -> List.of(), 2, 60).retrieve(query))
                .hasSize(2);
    }

    @Test
    void bm25FailureFallsBackToEmpty() {
        var jdbc = mock(JdbcTemplate.class);
        var retriever = new PgBm25ContentRetriever(jdbc, "agent_embedding", 5);
        when(jdbc.query(
                        anyString(),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        any(),
                        any()))
                .thenThrow(new IllegalStateException("offline"));
        assertThat(retriever.retrieve(query)).isEmpty();
    }

    @Test
    void bm25EmptyQuerySkipsJdbc() {
        var jdbc = mock(JdbcTemplate.class);
        var retriever = new PgBm25ContentRetriever(jdbc, "agent_embedding", 5);
        Query empty = mock(Query.class);
        when(empty.text()).thenReturn(" ");
        assertThat(retriever.retrieve(empty)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void rerankSortsAndFiltersScores() {
        when(scoring.scoreAll(anyList(), anyString()))
                .thenReturn(Response.from(List.of(.1, .9, .5)));
        assertThat(new RerankingContentRetriever(q -> hits, scoring, 2, .2).retrieve(query))
                .extracting(c -> c.textSegment().text())
                .containsExactly("B", "C");
    }

    @Test
    void rerankAllLowKeepsBestCandidatesAsInCourse() {
        when(scoring.scoreAll(anyList(), anyString()))
                .thenReturn(Response.from(List.of(.01, .03, .02)));
        assertThat(new RerankingContentRetriever(q -> hits, scoring, 2, .2).retrieve(query))
                .extracting(c -> c.textSegment().text())
                .containsExactly("B", "C");
    }

    @Test
    void rerankFailureKeepsOriginalTopK() {
        when(scoring.scoreAll(anyList(), anyString()))
                .thenThrow(new IllegalStateException("offline"));
        assertThat(new RerankingContentRetriever(q -> hits, scoring, 2, .2).retrieve(query))
                .containsExactly(hits.get(0), hits.get(1));
    }

    @Test
    void singletonSkipsRerankAsInCourse() {
        assertThat(
                        new RerankingContentRetriever(q -> List.of(hits.get(0)), scoring, 2, .2)
                                .retrieve(query))
                .hasSize(1);
        verifyNoInteractions(scoring);
    }

    @Test
    void emptyRecallSkipsRerank() {
        assertThat(new RerankingContentRetriever(q -> List.of(), scoring, 2, .2).retrieve(query))
                .isEmpty();
        verifyNoInteractions(scoring);
    }

    @Test
    void rerankMissingScoresUsesZeroAsInReference() {
        when(scoring.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(.9)));
        assertThat(new RerankingContentRetriever(q -> hits, scoring, 2, .2).retrieve(query))
                .containsExactly(hits.get(0));
    }

    @Test
    void skillCatalogLoadsAndBodyIsDisclosedOnDemand() {
        var skill = new SkillTool();
        skill.load();
        assertThat(skill.catalog()).contains("kb-curator").doesNotContain("用户资料使用规程");
        assertThat(skill.loadSkill("kb-curator"))
                .contains("用户资料使用规程", "searchKnowledge", "不调用 addKnowledgeToRag");
        assertThat(skill.loadSkill("missing")).contains("没有名为");
    }

    @Test
    void toolKnowledgeUsesReferenceHeadingAndDefaultFile() throws Exception {
        var ingestor = mock(EmbeddingStoreIngestor.class);
        var tool = new RagTool();
        var policy = mock(cn.qingliao.aiservice.knowledge.KnowledgeWritePolicy.class);
        when(policy.allowed(1L)).thenReturn(true);
        ReflectionTestUtils.setField(tool, "writePolicy", policy);
        cn.qingliao.aiservice.Monitor.MonitorContextHolder.setContext(
                cn.qingliao.aiservice.Monitor.MonitorContext.builder()
                        .userId(1L)
                        .sessionId(1L)
                        .build());
        ReflectionTestUtils.setField(tool, "docsPath", temp.toString());
        ReflectionTestUtils.setField(tool, "embeddingStoreIngestor", ingestor);
        assertThat(tool.addKnowledgeToRag("模型是什么？", "qwen3-max", null)).contains("成功");
        assertThat(Files.readString(temp.resolve("Qingliao.md"))).contains("### Q. 模型是什么？");
        var captor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(ingestor).ingest(captor.capture());
        assertThat(captor.getValue().metadata().getString("file_name")).isEqualTo("Qingliao.md");
    }

    @Test
    void toolRejectsEmbeddedHeadingsAsInReference() throws Exception {
        var ingestor = mock(EmbeddingStoreIngestor.class);
        var tool = new RagTool();
        var policy = mock(cn.qingliao.aiservice.knowledge.KnowledgeWritePolicy.class);
        when(policy.allowed(1L)).thenReturn(true);
        ReflectionTestUtils.setField(tool, "writePolicy", policy);
        cn.qingliao.aiservice.Monitor.MonitorContextHolder.setContext(
                cn.qingliao.aiservice.Monitor.MonitorContext.builder()
                        .userId(1L)
                        .sessionId(1L)
                        .build());
        ReflectionTestUtils.setField(tool, "docsPath", temp.toString());
        ReflectionTestUtils.setField(tool, "embeddingStoreIngestor", ingestor);
        assertThat(tool.addKnowledgeToRag("问题", "### 新块", null)).contains("保存失败");
        try (var files = Files.list(temp)) {
            assertThat(files.count()).isZero();
        }
        verifyNoInteractions(ingestor);
    }

    @Test
    void toolKnowledgeReportsReferencePartialFailure() throws Exception {
        var ingestor = mock(EmbeddingStoreIngestor.class);
        doThrow(new IllegalStateException("offline")).when(ingestor).ingest(any(Document.class));
        var tool = new RagTool();
        var policy = mock(cn.qingliao.aiservice.knowledge.KnowledgeWritePolicy.class);
        when(policy.allowed(1L)).thenReturn(true);
        ReflectionTestUtils.setField(tool, "writePolicy", policy);
        cn.qingliao.aiservice.Monitor.MonitorContextHolder.setContext(
                cn.qingliao.aiservice.Monitor.MonitorContext.builder()
                        .userId(1L)
                        .sessionId(1L)
                        .build());
        ReflectionTestUtils.setField(tool, "docsPath", temp.toString());
        ReflectionTestUtils.setField(tool, "embeddingStoreIngestor", ingestor);
        assertThat(tool.addKnowledgeToRag("问题", "答案", "new")).contains("文件写入成功，但向量数据库更新失败");
        assertThat(Files.readString(temp.resolve("new.md"))).contains("### Q. 问题");
    }

    @Test
    void unauthorizedKnowledgeToolCannotWriteOrIngest() throws Exception {
        var tool = new RagTool();
        var policy = mock(cn.qingliao.aiservice.knowledge.KnowledgeWritePolicy.class);
        var ingestor = mock(EmbeddingStoreIngestor.class);
        ReflectionTestUtils.setField(tool, "writePolicy", policy);
        ReflectionTestUtils.setField(tool, "docsPath", temp.toString());
        ReflectionTestUtils.setField(tool, "embeddingStoreIngestor", ingestor);
        cn.qingliao.aiservice.Monitor.MonitorContextHolder.setContext(
                cn.qingliao.aiservice.Monitor.MonitorContext.builder()
                        .userId(2002L)
                        .sessionId(1L)
                        .build());
        assertThat(tool.addKnowledgeToRag("Q", "A", null)).contains("维护权限");
        verifyNoInteractions(ingestor);
        try (var files = Files.list(temp)) {
            assertThat(files.count()).isZero();
        }
    }
}
