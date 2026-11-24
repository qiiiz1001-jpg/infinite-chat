package cn.qingliao.common.constant;

public final class UserStateConstant {
    private UserStateConstant() {
        // 工具类，禁止实例化
    }

    // ===================== 用户状态值（User State）=====================
    public static final int STATE_NORMAL = 0;
    public static final int STATE_BANNED = 1;
    public static final int STATE_CANCELLED = 2;
    // ===================== 状态描述（State Description）=====================
    public static final String DESC_NORMAL = "正常";
    public static final String DESC_BANNED = "封禁";
    public static final String DESC_CANCELLED = "注销";
    public static final String DESC_NOT_EXIST = "用户不存在";
    public static final String DESC_UNKNOWN = "未知";

    public static String getStateName(Integer state) {
        if (state == null) {
            return DESC_NOT_EXIST;
        }
        return switch (state) {
            case STATE_NORMAL -> DESC_NORMAL;
            case STATE_BANNED -> DESC_BANNED;
            case STATE_CANCELLED -> DESC_CANCELLED;
            default -> DESC_UNKNOWN;
        };
    }
}
