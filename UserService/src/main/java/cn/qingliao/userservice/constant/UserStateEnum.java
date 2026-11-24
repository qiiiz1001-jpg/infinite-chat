package cn.qingliao.userservice.constant;

public enum UserStateEnum {
    NORMAL(0, "正常"),
    BANNED(1, "封禁"),
    CANCELLED(2, "注销");
    private final int code;
    private final String description;

    UserStateEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static UserStateEnum fromCode(int code) {
        for (UserStateEnum state : UserStateEnum.values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("无效的用户状态码: " + code);
    }
}
