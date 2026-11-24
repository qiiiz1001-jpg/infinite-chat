package cn.qingliao.aiservice.controller;

import cn.qingliao.aiservice.Monitor.MonitorContext;
import cn.qingliao.aiservice.Monitor.MonitorContextHolder;
import cn.qingliao.aiservice.ai.AiChat;
import cn.qingliao.aiservice.ai.ConversationSummarizer;
import cn.qingliao.aiservice.model.dto.KnowledgeRequest;
import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ResultUtils;
import cn.qingliao.common.model.dto.ChatRequest;
import cn.qingliao.common.model.dto.ConversationSummaryInput;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

@org.springframework.web.bind.annotation.RequestMapping("/api/ai")
@cn.qingliao.common.security.RequireActor
@RestController
@Slf4j
public class AiChatController {
    @Resource private ConversationSummarizer conversationSummarizer;
    @Resource private cn.qingliao.aiservice.ai.AiChatService chatServices;

    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest chatRequest) {
        authorizeSession(chatRequest);
        MonitorContextHolder.setContext(
                MonitorContext.builder()
                        .userId(chatRequest.getUserId())
                        .sessionId(chatRequest.getSessionId())
                        .build());
        try {
            return chatServices
                    .forActor(
                            chatRequest.getUserId(),
                            chatRequest.getSessionId(),
                            chatRequest.getReferenceSessionId())
                    .chat(chatRequest.getSessionId(), chatRequest.getPrompt());
        } finally {
            MonitorContextHolder.clearContext();
        }
    }

    @PostMapping("/streamChat")
    public Flux<String> streamChat(@RequestBody ChatRequest chatRequest) {
        authorizeSession(chatRequest);
        MonitorContext context =
                MonitorContext.builder()
                        .userId(chatRequest.getUserId())
                        .sessionId(chatRequest.getSessionId())
                        .build();

        AiChat actorChat =
                chatServices.forActor(
                        chatRequest.getUserId(),
                        chatRequest.getSessionId(),
                        chatRequest.getReferenceSessionId());
        return Flux.defer(
                () -> {
                    MonitorContextHolder.setContext(context);
                    try {
                        return actorChat.streamChat(
                                chatRequest.getSessionId(), chatRequest.getPrompt());
                    } finally {
                        // 在设置监控上下文的线程清理；资料工具的权限身份由不可变字段绑定。
                        MonitorContextHolder.clearContext();
                    }
                });
    }

    /** 旧共享写入入口停用，资料需由用户上传并明确选择范围。 */
    @Deprecated
    @PostMapping("/insert")
    public String insertKnowledge(@RequestBody KnowledgeRequest knowledgeRequest) {
        throw cn.qingliao.aiservice.knowledge.KnowledgeAccess.invalid(
                "共享知识写入已停用，请在资料页上传文件并选择个人或会话范围。");
    }

    @PostMapping("/internal/summary")
    public BaseResponse<String> chatSummary(@RequestBody ConversationSummaryInput input) {
        return ResultUtils.success(conversationSummarizer.summarize(input.historyLog()));
    }

    @Resource private cn.qingliao.aiservice.client.SessionAccessClient sessions;

    private void authorizeSession(ChatRequest request) {
        if (request == null || request.getPrompt() == null || request.getPrompt().isBlank())
            throw new cn.qingliao.common.exception.BusinessException(
                    cn.qingliao.common.common.ErrorCode.PARAMS_ERROR, "消息不能为空");
        Long userId =
                cn.qingliao.common.security.CurrentUser.internal()
                        ? request.getUserId()
                        : cn.qingliao.common.security.CurrentUser.id();
        if (userId == null
                || userId <= 0
                || request.getSessionId() == null
                || !sessions.sessions(userId).contains(request.getSessionId()))
            throw new cn.qingliao.common.exception.BusinessException(
                    cn.qingliao.common.common.ErrorCode.NO_AUTH_ERROR);
        request.setUserId(userId);
    }
}
