package cn.qingliao.aiservice.config;

import dev.langchain4j.community.model.dashscope.QwenScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RerankModelConfig {

    @Value("${langchain4j.community.dashscope.chat-model.api-key}")
    private String apiKey;

    @Value("${rerank.model-name:qwen3-rerank}")
    private String rerankModelName;

    @Bean
    public ScoringModel scoringModel() {
        return QwenScoringModel.builder().apiKey(apiKey).modelName(rerankModelName).build();
    }
}
