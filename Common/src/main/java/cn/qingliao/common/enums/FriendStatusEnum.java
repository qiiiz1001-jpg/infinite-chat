package cn.qingliao.common.enums;

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
}
