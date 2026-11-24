package cn.qingliao.redpacketservice.config;

import cn.qingliao.redpacketservice.constant.KafkaConstant;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    public static final String TOPIC_REDPACKET_CREATION = "redpacket-creation-topic";
    public static final String TOPIC_REDPACKET_RECEIVE = "topic-redpacket-receive";
    public static final String TOPIC_REDPACKET_COMPLETED = "topic-redpacket-completed";
    public static final String TOPIC_REDPACKET_EXPIRATION = "redpacket-expiration-topic";

    @Bean
    public NewTopic redpacketCreationTopic() {
        return TopicBuilder.name(TOPIC_REDPACKET_CREATION)
                .partitions(KafkaConstant.DEFAULT_PARTITION_COUNT)
                .replicas(KafkaConstant.DEFAULT_REPLICA_COUNT)
                .build();
    }

    @Bean
    public NewTopic redpacketReceiveTopic() {
        return TopicBuilder.name(TOPIC_REDPACKET_RECEIVE)
                .partitions(KafkaConstant.DEFAULT_PARTITION_COUNT)
                .replicas(KafkaConstant.DEFAULT_REPLICA_COUNT)
                .build();
    }

    @Bean
    public NewTopic redpacketCompletedTopic() {
        return TopicBuilder.name(TOPIC_REDPACKET_COMPLETED)
                .partitions(KafkaConstant.DEFAULT_PARTITION_COUNT)
                .replicas(KafkaConstant.DEFAULT_REPLICA_COUNT)
                .build();
    }

    @Bean
    public NewTopic redpacketExpirationTopic() {
        return TopicBuilder.name(TOPIC_REDPACKET_EXPIRATION)
                .partitions(KafkaConstant.DEFAULT_PARTITION_COUNT)
                .replicas(KafkaConstant.DEFAULT_REPLICA_COUNT)
                .build();
    }
}
