package cn.qingliao.redpacketservice.constant;

public final class KafkaConstant {
    private KafkaConstant() {
        // 私有构造函数，防止实例化
    }

    // ==================== Kafka Topic 配置 ====================
    public static final int DEFAULT_PARTITION_COUNT = 3;
    public static final int DEFAULT_REPLICA_COUNT = 1;
    // ==================== Kafka 消费者配置 ====================
    public static final String DEFAULT_CONCURRENCY = "3";
    // ==================== 消费者组 ID ====================
    public static final String GROUP_RECEIVE_HANDLER = "redpacket-receive-handler-group";
    public static final String GROUP_COMPLETED_HANDLER = "redpacket-completed-handler-group";
    public static final String GROUP_DELAY_TASK_ENQUEUER = "delay-task-enqueuer-group";
    public static final String GROUP_EXPIRATION_EXECUTOR = "expiration-task-executor-group";
}
