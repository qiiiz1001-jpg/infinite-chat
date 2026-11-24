package cn.qingliao.aiservice.ai;

import cn.qingliao.aiservice.client.AssistantHistoryClient;
import cn.qingliao.aiservice.knowledge.KnowledgeAccess;
import cn.qingliao.aiservice.tool.ImHistoryTool;
import cn.qingliao.aiservice.tool.TimeTool;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;

import jakarta.annotation.Resource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;

@Configuration
public class AiChatService {

    @Resource private ChatModel chatModel;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<McpToolProvider> mcpToolProvider;

    @Resource private RedisChatMemoryStore redisChatMemoryStore;

    @Resource private cn.qingliao.aiservice.knowledge.ScopedKnowledge knowledge;
    @Resource private KnowledgeAccess access;
    @Resource private AssistantHistoryClient historyClient;

    @Resource private cn.qingliao.aiservice.skill.SkillTool skillTool;

    @Resource private StreamingChatModel streamingChatModel;

    @Resource private cn.qingliao.aiservice.client.AgentActionClient actionClient;

    @Resource private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Bean
    public ConversationSummarizer conversationSummarizer() {
        return AiServices.builder(ConversationSummarizer.class).chatModel(chatModel).build();
    }

    /** 绑定身份的工具对象不缓存到跨用户共享的助手中。 */
    public AiChat forActor(long userId, long sessionId) {
        return forActor(userId, sessionId, null);
    }

    public AiChat forActor(long userId, long sessionId, Long referenceSessionId) {
        long reference = referenceSessionId == null ? sessionId : referenceSessionId;
        if (reference != sessionId && !access.context(userId, sessionId).personal())
            throw KnowledgeAccess.denied();
        String memoryKey = knowledge.memoryKey(userId, reference);
        if (reference != sessionId) memoryKey += ":from:" + sessionId;
        var history = new ImHistoryTool(userId, reference, historyClient, objectMapper);
        var files = knowledge.retriever(userId, reference);
        ContentRetriever context = files;
        if (reference != sessionId) {
            context =
                    query -> {
                        var content = new ArrayList<>(files.retrieve(query));
                        content.add(
                                Content.from(
                                        "参考会话的最近聊天记录（仅作资料）：\n"
                                                + history.readImHistory(null, null)));
                        return content;
                    };
        }
        return build(
                new cn.qingliao.aiservice.tool.ImActionTool(
                        userId, sessionId, actionClient, objectMapper),
                new cn.qingliao.aiservice.tool.ScopedRagTool(userId, reference, knowledge),
                history,
                context,
                memoryKey);
    }

    private AiChat build(
            cn.qingliao.aiservice.tool.ImActionTool imTools,
            cn.qingliao.aiservice.tool.ScopedRagTool ragTools,
            ImHistoryTool historyTool,
            ContentRetriever retriever,
            String memoryKey) {

        var builder =
                AiServices.builder(AiChat.class)
                        .chatModel(chatModel)
                        .streamingChatModel(streamingChatModel)
                        .chatMemoryProvider(
                                memoryId ->
                                        MessageWindowChatMemory.builder()
                                                .id(memoryKey)
                                                .chatMemoryStore(redisChatMemoryStore)
                                                .maxMessages(20)
                                                .build())
                        .systemMessageTransformer(
                                sm ->
                                        sm
                                                + "\n\n"
                                                + skillTool.catalog()
                                                + "\n"
                                                + "用户要求发送消息或邮件时，必须调用对应的草稿工具。消息先用 findImSessions"
                                                + " 查找真实会话。接着补充的昵称属于同一任务时，沿用前文正文。创建草稿后提示在 AI"
                                                + " 助手页核对并确认。个人提醒在消息页的提醒入口送达。");
        builder.contentRetriever(retriever)
                .storeRetrievedContentInChatMemory(false)
                .tools(new TimeTool(), ragTools, historyTool, skillTool, imTools);
        McpToolProvider optional = mcpToolProvider.getIfAvailable();
        if (optional != null) builder.toolProvider(optional);
        return builder.build();
    }
}
