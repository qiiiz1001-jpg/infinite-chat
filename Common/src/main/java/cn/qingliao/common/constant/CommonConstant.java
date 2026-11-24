package cn.qingliao.common.constant;

import java.util.concurrent.TimeUnit;

public class CommonConstant {

    public static final String TOKEN_SECRET_KEY = System.getenv("JWT_SECRET_BASE64");

    public static final Integer ACCESS_TOKEN_EXPIRE_TIME = 30;

    public static final Integer REFRESH_TOKEN_EXPIRE_TIME = 7;

    public static final String ACCESS_TOKEN_PREFIX = "access:token:";

    public static final String REFRESH_TOKEN_PREFIX = "refresh:token:";

    public static final TimeUnit ACCESS_TOKEN_UNIT = TimeUnit.MINUTES;

    public static final TimeUnit REFRESH_TOKEN_UNIT = TimeUnit.DAYS;

    public static final String KAFKA_MESSAGE_TOPIC_STORE = "store-topic";

    public static final String KAFKA_MESSAGE_TOPIC_PUSH = "message-topic";

    public static final String REDIS_NETTY_URI = "nettyUri";

    private CommonConstant() {}

    public static final String DISCOVERY_CLIENT_NAME = "RealTimeService";

    public static final String NETTY_SERVICE_URI = "/ws/netty";

    public static final String BUCKET_NAME =
            System.getenv().getOrDefault("MINIO_BUCKET", "qingliao");

    public static final Integer PICTURE_EXPIRE_TIME = 3000;

    public static final String OFFLINE_KEY_REDIS = "user:offline:";

    public static final String SESSION_KEY_REDIS = "session:";

    public static final Long SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000;

    public static final Integer DEFAULT_LIMIT = 20;

    public static final Long AI_ID = 111111111L;

    public static final Integer USER_ROLE_NORMAL = 2;

    public static final Integer SESSION_STATUS = 0;

    public static final String KAFKA_SYSTEM_NOTIFICATION_TOPIC = "system-notification-topic";

    public static final String KAFKA_STORE_NOTIFICATION_TOPIC = "store-notification-topic";
}
