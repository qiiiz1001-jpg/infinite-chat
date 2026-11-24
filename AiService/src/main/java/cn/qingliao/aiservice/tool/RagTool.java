package cn.qingliao.aiservice.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class RagTool {

    @Resource private EmbeddingStoreIngestor embeddingStoreIngestor;

    @Resource
    @Qualifier("rerankingContentRetriever")
    private ContentRetriever contentRetriever;

    @Value("${rag.docs-path}")
    private String docsPath;

    /** 主动查重入口，只返回检索反馈，后续是否写入仍由模型决定。 */
    @Tool("在知识库里检索与给定问题最相近的已有条目。用于入库前查重，或需要确认知识库里到底有没有某条内容时。")
    public String searchKnowledge(@P("要检索的核心问题或关键词，不要传用户整段原话") String query) {
        if (query == null || query.isBlank()) {
            return "检索关键词不能为空。";
        }
        try {
            List<Content> contents = contentRetriever.retrieve(Query.from(query));
            log.info("Tool 调用: 知识库查重 - Q: {} | 命中 {} 条", query, contents.size());
            if (contents.isEmpty()) {
                return "知识库中没有检索到相近条目，可视为新知识。";
            }
            String hits =
                    contents.stream()
                            .map(c -> c.textSegment().text())
                            .collect(Collectors.joining("\n\n--------\n\n"));
            return "检索到 " + contents.size() + " 条相近内容，请判断是重复、冲突还是无关：\n\n" + hits;
        } catch (Exception e) {
            log.error("知识库查重失败: {}", query, e);
            return "知识库检索失败：" + e.getMessage() + "。请如实告知用户，不要在没查重的情况下直接入库。";
        }
    }

    /** 自然语言知识入库：先追加文件，再更新向量库。 */
    @Tool("当用户想要保存问答对、知识点或者向知识库添加新信息时调用此工具。将问题、答案和目标文件名作为参数。")
    public String addKnowledgeToRag(String question, String answer, String fileName) {
        var actor = cn.qingliao.aiservice.Monitor.MonitorContextHolder.getContext();
        if (actor == null || !writePolicy.allowed(actor.getUserId())) {
            return "当前用户没有共享知识库维护权限。";
        }
        log.info("Tool 调用: 正在保存知识 - Q: {}, file: {}", question, fileName);

        if ((question + "\n" + answer)
                .lines()
                .anyMatch(line -> line.stripLeading().startsWith("###"))) {
            return "保存失败：问题或答案里不能有以 ### 开头的行，它会被切分器当成新块的起点，把这条知识劈成两半。" + "请改写成「三个井号」这类文字表述后重新调用。";
        }

        String formattedContent = String.format("### Q. %s\n\nA：%s", question, answer);

        if (fileName == null || fileName.isBlank()) {
            fileName = "Qingliao.md"; // 默认文件
        }
        if (!fileName.endsWith(".md")) {
            fileName = fileName + ".md";
        }

        boolean writeSuccess = appendToFile(formattedContent, fileName);
        if (!writeSuccess) {
            return "保存失败：无法写入本地文件系统，请检查日志。";
        }

        try {
            Metadata metadata = Metadata.from("file_name", fileName);

            Document document = Document.from(formattedContent, metadata);
            embeddingStoreIngestor.ingest(document);

            log.info("Tool 执行成功: 知识已同步至 RAG");
            return "成功！已将该知识点保存到文档 [" + fileName + "] 并同步至向量数据库。";
        } catch (Exception e) {
            log.error("RAG - 向量化失败", e);
            return "文件写入成功，但向量数据库更新失败：" + e.getMessage();
        }
    }

    private boolean appendToFile(String content, String fileName) {
        try {
            cn.qingliao.aiservice.knowledge.KnowledgeFiles.append(docsPath, fileName, content);
            return true;
        } catch (java.io.IOException error) {
            log.warn("知识文件写入失败或路径被拒绝");
            return false;
        }
    }

    @Resource private cn.qingliao.aiservice.knowledge.KnowledgeWritePolicy writePolicy;
}
