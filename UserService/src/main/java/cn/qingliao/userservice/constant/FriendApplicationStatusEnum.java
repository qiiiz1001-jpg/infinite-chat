package cn.qingliao.userservice.constant;

public enum FriendApplicationStatusEnum {
    UNREAD(0, "未读"),
    ACCEPTED(1, "通过"),
    REJECTED(2, "拒绝"),
    READ(3, "已读"),
    EXPIRED(4, "过期");
    private final int code;
    private final String description;

    FriendApplicationStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static FriendApplicationStatusEnum fromCode(int code) {
        for (FriendApplicationStatusEnum status : FriendApplicationStatusEnum.values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("无效的好友申请状态码: " + code);
    }
}
