package cn.qingliao.aiservice.tool;

import cn.qingliao.aiservice.knowledge.ScopedKnowledge;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.rag.content.Content;

import java.util.stream.Collectors;

/** 模型只读用户上传的可见资料，没有共享库写入入口。 */
public record ScopedRagTool(long userId, long sessionId, ScopedKnowledge knowledge) {
    @Tool("检索当前参考会话有权使用的用户上传资料。个人资料仅在以本人的助手会话作为参考范围时可用；单聊和群聊只查会话共享资料。引用文件名和片段号。")
    public String searchKnowledge(String question) {
        var hits = knowledge.retrieve(userId, sessionId, question);
        return hits.isEmpty()
                ? "当前可见知识库没有找到资料；请用户上传相关文件，不得猜测。"
                : hits.stream()
                        .map(ScopedRagTool::formatHit)
                        .collect(Collectors.joining("\n\n---\n\n"));
    }

    private static String formatHit(Content hit) {
        var segment = hit.textSegment();
        String filename = segment.metadata().getString("file_name");
        Integer chunk = segment.metadata().getInteger("chunk_no");
        return filename != null && chunk != null
                ? filename + "（片段 " + chunk + "）\n" + segment.text()
                : segment.text();
    }
}
