package cn.qingliao.common.constant;

public final class ValidationRedisKey {
    private ValidationRedisKey() {
        // 工具类，禁止实例化
    }

    // ===================== Redis 键前缀（Redis Key Prefix）=====================
    public static final String FRIEND_STATUS_KEY_PREFIX = "msg:validate:friend:status:";
    // ===================== 缓存值（Cache Values）=====================
    public static final String NON_FRIEND_CACHE_VALUE = "-1";
    // ===================== 缓存 TTL（Cache TTL）=====================
    public static final long FRIEND_STATUS_TTL_MINUTES = 5;

    // ===================== 工具方法（Utility Methods）=====================
    public static String buildFriendStatusKey(Long userId, Long friendId) {
        return FRIEND_STATUS_KEY_PREFIX + userId + ":" + friendId;
    }

    public static boolean isNonFriendValue(String cachedValue) {
        return NON_FRIEND_CACHE_VALUE.equals(cachedValue);
    }
}
