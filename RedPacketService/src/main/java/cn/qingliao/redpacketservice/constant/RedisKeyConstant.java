package cn.qingliao.redpacketservice.constant;

public final class RedisKeyConstant {

    public static final String POOL_TEMPLATE = "redpacket:%s:pool";

    public static final String RECORDS_TEMPLATE = "redpacket:%s:records";

    public static final String EXPIRE_ZSET = "redpacket-expire-zset";

    public static final String PREVENT_DUPLICATE_PREFIX = "prevent:duplicate:";

    public static String getPoolKey(Long redPacketId) {
        return String.format(POOL_TEMPLATE, redPacketId);
    }

    public static String getPoolKey(String redPacketId) {
        return String.format(POOL_TEMPLATE, redPacketId);
    }

    public static String getRecordsKey(Long redPacketId) {
        return String.format(RECORDS_TEMPLATE, redPacketId);
    }

    private RedisKeyConstant() {
        // 私有构造函数，防止实例化
    }

    public static final String STATE_TEMPLATE = "redpacket:%s:state";

    public static final String GRANT_TEMPLATE = "redpacket:%s:grant";

    public static final String ALLOW_TEMPLATE = "redpacket:%s:allow";

    public static String getStateKey(Long redPacketId) {
        return String.format(STATE_TEMPLATE, redPacketId);
    }

    public static String getGrantKey(Long redPacketId) {
        return String.format(GRANT_TEMPLATE, redPacketId);
    }

    public static String getAllowKey(Long redPacketId) {
        return String.format(ALLOW_TEMPLATE, redPacketId);
    }
}
