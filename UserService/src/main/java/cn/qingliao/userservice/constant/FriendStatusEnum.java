package cn.qingliao.userservice.constant;

public enum FriendStatusEnum {
    NON_FRIEND(-1, "非好友"),
    NORMAL(0, "好友"),
    BLOCKED(1, "拉黑"),
    DELETED(2, "删除");
    private final int code;
    private final String description;

    FriendStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static FriendStatusEnum fromCode(int code) {
        for (FriendStatusEnum status : FriendStatusEnum.values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("无效的好友状态码: " + code);
    }
}
