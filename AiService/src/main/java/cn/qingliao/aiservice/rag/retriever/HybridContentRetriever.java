package cn.qingliao.aiservice.rag.retriever;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class HybridContentRetriever implements ContentRetriever {

    private final ContentRetriever vectorRetriever;
    private final ContentRetriever bm25Retriever;
    private final int maxResults;
    private final double rrfK;

    public HybridContentRetriever(
            ContentRetriever vectorRetriever,
            ContentRetriever bm25Retriever,
            int maxResults,
            double rrfK) {
        this.vectorRetriever = vectorRetriever;
        this.bm25Retriever = bm25Retriever;
        this.maxResults = maxResults;
        this.rrfK = rrfK;
    }

    /** 顺序执行两路召回，按完整正文去重后做 RRF 排名融合。 */
    @Override
    public List<Content> retrieve(Query query) {
        List<Content> vectorHits = vectorRetriever.retrieve(query);
        List<Content> bm25Hits = bm25Retriever.retrieve(query);

        Map<String, Double> scoreByKey = new LinkedHashMap<>();
        Map<String, Content> contentByKey = new LinkedHashMap<>();

        accumulate(vectorHits, scoreByKey, contentByKey);
        accumulate(bm25Hits, scoreByKey, contentByKey);

        List<Content> fused =
                scoreByKey.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .limit(maxResults)
                        .map(e -> contentByKey.get(e.getKey()))
                        .collect(Collectors.toList());

        log.info(
                "混合检索 - 向量 {} 条 / BM25 {} 条 / 融合去重后取 top-{} = {} 条",
                vectorHits.size(),
                bm25Hits.size(),
                maxResults,
                fused.size());
        return fused;
    }

    // 排名从 1 开始；累加名次分，不直接混加向量分和 BM25 分。
    private void accumulate(
            List<Content> hits, Map<String, Double> scoreByKey, Map<String, Content> contentByKey) {
        for (int i = 0; i < hits.size(); i++) {
            Content content = hits.get(i);
            String key = content.textSegment().text();
            contentByKey.putIfAbsent(key, content);
            scoreByKey.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
        }
    }
}
