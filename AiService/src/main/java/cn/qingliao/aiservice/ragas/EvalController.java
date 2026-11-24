package cn.qingliao.aiservice.ragas;

import cn.qingliao.aiservice.Monitor.MonitorContext;
import cn.qingliao.aiservice.Monitor.MonitorContextHolder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@cn.qingliao.common.security.RequireActor
@RequestMapping("/api/ai/eval")
@Profile({"dev", "test"}) // 仅在 dev/test 环境启用，避免误上生产
@Slf4j
public class EvalController {

    private static final Long EVAL_USER_ID = 0L;
    private static final Long EVAL_SESSION_ID = 0L;

    @Resource private ContentRetriever rerankingContentRetriever;

    @Resource private ChatModel chatModel;
    @Resource private cn.qingliao.aiservice.knowledge.KnowledgeWritePolicy writePolicy;

    /** 用同一批召回资料生成答案并原样返回 contexts，不走会话工具代理。 */
    @PostMapping("/rag")
    public Map<String, Object> evalRag(@RequestBody Map<String, String> body) {
        if (!writePolicy.allowed(cn.qingliao.common.security.CurrentUser.id()))
            throw cn.qingliao.aiservice.knowledge.KnowledgeAccess.denied();
        String question = body.get("question");

        MonitorContextHolder.setContext(
                MonitorContext.builder().userId(EVAL_USER_ID).sessionId(EVAL_SESSION_ID).build());
        try {
            List<Content> contents = rerankingContentRetriever.retrieve(Query.from(question));
            List<String> contexts =
                    contents.stream().map(c -> c.textSegment().text()).collect(Collectors.toList());

            String joinedContext =
                    contexts.isEmpty() ? "（未检索到任何资料）" : String.join("\n\n---\n\n", contexts);

            String prompt =
                    """
                    你是"轻聊"知识库问答助手。请严格根据下面的【参考资料】回答用户问题，并遵守以下要求：

                    1.【忠于资料】只依据参考资料作答，不得编造、推测或补充资料中未提及的信息；若参考资料中找不到答案，直接回答「根据现有资料无法回答」，此时无需复述问题。
                    2.【自包含】能作答时，先用一句话把问题所问的核心对象/主语复述进来，再给结论，让人单看这句答案就知道在回答什么：
                       - 问"第五节课什么时候录的？" → 答"第五节课录制于 2026-1-28。"（不要只回"2026-1-28"）
                       - 问"类型安全上有什么好处？" → 答"LangChain4j 在类型安全上的好处是编译期类型检查，可减少运行时错误。"
                    3.【精炼】在自包含的前提下保持简洁，一般 1~3 句即可，不啰嗦、不堆砌无关内容。

                    【参考资料】
                    %s

                    【用户问题】
                    %s
                    """
                            .formatted(joinedContext, question);

            String answer = chatModel.chat(prompt);

            log.info("RAG 评测 - Q: {} | 命中上下文 {} 条", question, contexts.size());
            return Map.of(
                    "question", question,
                    "answer", answer,
                    "contexts", contexts);
        } finally {
            MonitorContextHolder.clearContext();
        }
    }
}
