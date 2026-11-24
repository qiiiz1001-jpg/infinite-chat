package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.qingliao.aiservice.client.SessionAccessClient;
import cn.qingliao.aiservice.knowledge.*;
import cn.qingliao.aiservice.tool.ScopedRagTool;
import cn.qingliao.common.model.dto.KnowledgeSession;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class ScopedKnowledgeTest {
    SessionAccessClient sessions = mock(SessionAccessClient.class);
    KnowledgeAccess access = new KnowledgeAccess(sessions);
    KnowledgeRepository repo = mock(KnowledgeRepository.class);
    EmbeddingModel embeddings = mock(EmbeddingModel.class);
    ScoringModel scoring = mock(ScoringModel.class);
    ScopedKnowledge knowledge = new ScopedKnowledge(access, repo, embeddings, scoring);
    KnowledgeService service = new KnowledgeService(access, repo, new KnowledgeParser());

    KnowledgeRepository.Doc doc(String scope, long owner, long session, String status) {
        return new KnowledgeRepository.Doc(
                "doc-1", owner, scope, session, "api.md", 10, status, 1, 1, "", 1, 1, "正文", "");
    }

    @Test
    void sessionFailureAndMissingMembershipFailClosedBeforeQuery() {
        when(sessions.knowledgeAccess(1L, 10L)).thenReturn(new KnowledgeSession(false, false));
        assertThrows(RuntimeException.class, () -> knowledge.retrieve(1, 10, "登录"));
        verifyNoInteractions(repo, embeddings, scoring);
        when(sessions.knowledgeAccess(1L, 10L)).thenThrow(new IllegalStateException("RPC offline"));
        assertThrows(RuntimeException.class, () -> knowledge.retrieve(1, 10, "登录"));
        assertThrows(RuntimeException.class, () -> access.space(1, "PERSONAL", 10L));
    }

    @Test
    void uploaderCannotReadOtherUsersPersonalOrDeleteAnotherMembersFile() {
        when(repo.find("doc-1")).thenReturn(doc("PERSONAL", 2, 0, "READY"));
        assertThrows(RuntimeException.class, () -> service.text(1, "doc-1"));
        when(repo.find("doc-1")).thenReturn(doc("SESSION", 2, 10, "READY"));
        when(sessions.knowledgeAccess(1L, 10L)).thenReturn(new KnowledgeSession(true, false));
        assertEquals("正文", service.text(1, "doc-1"));
        assertThrows(RuntimeException.class, () -> service.delete(1, "doc-1"));
        assertThrows(RuntimeException.class, () -> service.retry(1, "doc-1"));
        verify(repo, never()).delete(anyString());
    }

    @Test
    void bothRetrievalBranchesUseBoundScopeAcrossToolThreads() throws Exception {
        when(embeddings.embed(anyString()))
                .thenReturn(
                        dev.langchain4j.model.output.Response.from(
                                dev.langchain4j.data.embedding.Embedding.from(new float[1024])));
        when(sessions.knowledgeAccess(1L, 10L)).thenReturn(new KnowledgeSession(true, false));
        var metadata = Metadata.from("document_id", "doc-1");
        var hit = Content.from(TextSegment.from("资料内容", metadata));
        when(repo.keywords(any(), eq("登录"), anyInt())).thenReturn(List.of(hit));
        when(repo.find("doc-1")).thenReturn(doc("SESSION", 1, 10, "READY"));
        var tool = new ScopedRagTool(1, 10, knowledge);
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(
                    pool.submit(() -> tool.searchKnowledge("登录"))
                            .get(5, TimeUnit.SECONDS)
                            .contains("资料内容"));
        } finally {
            pool.shutdownNow();
        }
        var captured = org.mockito.ArgumentCaptor.forClass(KnowledgeAccess.Context.class);
        verify(repo).keywords(captured.capture(), eq("登录"), anyInt());
        assertEquals(1, captured.getValue().userId());
        assertEquals(10, captured.getValue().sessionId());
        assertFalse(captured.getValue().personal());
        verify(repo).vectors(eq(captured.getValue()), any(float[].class), anyInt());
    }

    @Test
    void deletedOrRevokedSourcesAreRemovedBeforeReturning() {
        when(sessions.knowledgeAccess(1L, 20L)).thenReturn(new KnowledgeSession(true, true));
        var hit = Content.from(TextSegment.from("旧资料", Metadata.from("document_id", "doc-1")));
        when(repo.keywords(any(), anyString(), anyInt())).thenReturn(List.of(hit));
        when(repo.find("doc-1")).thenReturn(doc("PERSONAL", 1, 0, "DELETED"));
        assertTrue(knowledge.retrieve(1, 20, "旧资料").isEmpty());
        when(sessions.knowledgeAccess(1L, 20L))
                .thenReturn(new KnowledgeSession(true, true), new KnowledgeSession(false, false));
        assertThrows(RuntimeException.class, () -> knowledge.retrieve(1, 20, "旧资料"));
    }

    @Test
    void memoryIsSeparatedByUserSessionAndVisibleDocumentRevision() {
        when(sessions.knowledgeAccess(anyLong(), anyLong()))
                .thenReturn(new KnowledgeSession(true, true));
        when(repo.memoryRevision(any())).thenReturn("revision1", "revision1", "revision2");
        assertNotEquals(knowledge.memoryKey(1, 20), knowledge.memoryKey(2, 20));
        assertTrue(knowledge.memoryKey(1, 20).endsWith("revision2"));
    }
}
