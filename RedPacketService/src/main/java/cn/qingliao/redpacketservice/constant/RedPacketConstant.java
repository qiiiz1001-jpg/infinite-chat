package cn.qingliao.redpacketservice.constant;

public final class RedPacketConstant {
    private RedPacketConstant() {
        // 私有构造函数，防止实例化
    }

    // ==================== 红包状态常量 ====================
    public static final int STATUS_NOT_COMPLETED = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_EXPIRED = 2;
    public static final int STATUS_NOT_EXIST = -1;
    // ==================== 红包类型常量 ====================
    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_RANDOM = 1;
    // ==================== 时间配置常量 ====================
    public static final long EXPIRE_TIME_MS = 24 * 60 * 60 * 1000L;
    public static final int REDIS_CACHE_EXPIRE_HOURS = 25;
    // ==================== 金额限制常量 ====================
    public static final int MAX_SINGLE_AMOUNT_YUAN = 200;
    // ==================== 金额转换常量 ====================
    public static final int YUAN_TO_FEN_MULTIPLIER = 100;

    // ==================== 辅助方法 ====================
    public static boolean isValidType(int type) {
        return type == TYPE_NORMAL || type == TYPE_RANDOM;
    }
}
