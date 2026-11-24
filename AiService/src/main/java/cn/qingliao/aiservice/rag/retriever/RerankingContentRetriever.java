package cn.qingliao.aiservice.rag.retriever;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class RerankingContentRetriever implements ContentRetriever {

    private final ContentRetriever baseRetriever; // 粗召回：混合检索
    private final ScoringModel scoringModel; // 精排：qwen3-rerank
    private final int topK; // 精排后最终保留条数
    private final double minScore; // 正常过滤阈值；单条跳过、全低分回退是例外。

    public RerankingContentRetriever(
            ContentRetriever baseRetriever, ScoringModel scoringModel, int topK, double minScore) {
        this.baseRetriever = baseRetriever;
        this.scoringModel = scoringModel;
        this.topK = topK;
        this.minScore = minScore;
    }

    /** 对粗召回候选精排；异常退回原候选的前 topK。 */
    @Override
    public List<Content> retrieve(Query query) {
        // 上游调用在 try 外，其异常不属于后面的精排降级范围。
        List<Content> candidates = baseRetriever.retrieve(query);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 原文单条候选直接返回，不执行打分和阈值检查。
        if (candidates.size() == 1) {
            return candidates;
        }

        try {
            List<TextSegment> segments = new ArrayList<>(candidates.size());
            for (Content c : candidates) {
                segments.add(c.textSegment());
            }
            List<Double> scores = scoringModel.scoreAll(segments, query.text()).content();

            List<Scored> scored = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                double s = (i < scores.size() && scores.get(i) != null) ? scores.get(i) : 0.0;
                scored.add(new Scored(candidates.get(i), s));
            }
            scored.sort(Comparator.comparingDouble((Scored x) -> x.score).reversed());

            List<Content> result = new ArrayList<>(topK);
            for (Scored x : scored) {
                if (x.score < minScore) {
                    continue;
                }
                result.add(x.content);
                if (result.size() >= topK) {
                    break;
                }
            }

            // 原文全低分时仍返回最高分候选，minScore 不是严格拒答门槛。
            if (result.isEmpty()) {
                for (Scored x : scored) {
                    result.add(x.content);
                    if (result.size() >= topK) {
                        break;
                    }
                }
                log.warn("重排 - 所有候选均低于阈值 {}，已回退为分数最高的 {} 条", minScore, result.size());
            }

            log.info(
                    "重排 - 粗召回 {} 条 / 精排后取 top-{} = {} 条 | Q: {}",
                    candidates.size(),
                    topK,
                    result.size(),
                    query.text());
            return result;

        } catch (Exception e) {
            log.error("重排失败(降级为粗召回前 {} 条): {}", topK, e.getMessage());
            return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
        }
    }

    private static final class Scored {
        final Content content;
        final double score;

        Scored(Content content, double score) {
            this.content = content;
            this.score = score;
        }
    }
}
