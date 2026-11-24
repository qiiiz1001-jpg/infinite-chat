package cn.qingliao.realtimeservice.config;

import cn.qingliao.common.constant.CommonConstant;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 当前单机学习环境使用单副本；会话 key 在同一分区内维持追加顺序。 */
@Configuration
public class KafkaTopicConfig {
    @Bean
    public NewTopic storeTopic() {
        return TopicBuilder.name(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic pushTopic() {
        return TopicBuilder.name(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic aiTopic() {
        return TopicBuilder.name("ai-request-topic").partitions(3).replicas(1).build();
    }
}
