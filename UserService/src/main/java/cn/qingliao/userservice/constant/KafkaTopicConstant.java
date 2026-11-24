package cn.qingliao.userservice.constant;

public class KafkaTopicConstant {
    public static final String TOPIC_SYSTEM_NOTIFICATION = "system-notification-topic";
    public static final String TOPIC_FRIEND_REQUEST_CREATION = "friend-request-creation-topic";
    public static final String TOPIC_FRIEND_REQUEST_EXPIRATION = "friend-request-expiration-topic";
    public static final String GROUP_SYSTEM_NOTIFICATION_CONSUMER =
            "system-notification-consumer-group";
    public static final String GROUP_FRIEND_REQUEST_ENQUEUER = "friend-request-enqueuer-group";
    public static final String GROUP_FRIEND_REQUEST_EXECUTOR = "friend-request-executor-group";
}
