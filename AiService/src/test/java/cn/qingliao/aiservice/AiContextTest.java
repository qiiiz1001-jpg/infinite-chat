package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.qingliao.aiservice.ai.ConversationSummarizer;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.model.dto.KnowledgeSession;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 验证模型、RAG、可选 MCP、公共配置能共同装配；不连接外部向量库或调用模型。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "spring.cloud.nacos.discovery.enabled=false",
            "langchain4j.community.dashscope.chat-model.api-key=test-key",
            "langchain4j.community.dashscope.streaming-chat-model.api-key=test-key",
            "langchain4j.community.dashscope.embedding-model.api-key=test-key",
            "mcp.enabled=false",
            "rag.load-on-startup=false",
            "rag.indexing.enabled=false"
        })
class AiContextTest {
    @MockitoBean private EmbeddingStore<TextSegment> embeddingStore;
    @MockitoBean private RedisChatMemoryStore redisChatMemoryStore;

    @MockitoBean(name = "pgDataSource")
    private javax.sql.DataSource pgDataSource;

    @MockitoBean private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @MockitoBean private cn.qingliao.aiservice.client.SessionAccessClient sessions;
    @Autowired private ConversationSummarizer conversationSummarizer;
    @Autowired private cn.qingliao.aiservice.ai.AiChatService chatServices;

    @Test
    void assemblesAiServiceWithoutExternalCalls() {
        org.mockito.Mockito.when(
                        sessions.knowledgeAccess(
                                org.mockito.ArgumentMatchers.anyLong(),
                                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new cn.qingliao.common.model.dto.KnowledgeSession(true, true));
        assertNotNull(conversationSummarizer);
        assertNotNull(chatServices.forActor(1001, 20));
        assertNotNull(chatServices.forActor(2002, 30));
    }

    @Test
    void onlyPrivateAssistantCanBindAnotherAccessibleConversation() {
        org.mockito.Mockito.when(sessions.knowledgeAccess(1L, 10L))
                .thenReturn(new KnowledgeSession(true, true));
        org.mockito.Mockito.when(sessions.knowledgeAccess(1L, 20L))
                .thenReturn(new KnowledgeSession(true, false));
        org.mockito.Mockito.when(sessions.knowledgeAccess(1L, 30L))
                .thenReturn(new KnowledgeSession(false, false));
        assertNotNull(chatServices.forActor(1, 10, 20L));
        assertThrows(BusinessException.class, () -> chatServices.forActor(1, 10, 30L));
        assertThrows(BusinessException.class, () -> chatServices.forActor(1, 20, 10L));
    }
}
