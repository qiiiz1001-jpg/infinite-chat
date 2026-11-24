package cn.qingliao.common.enums;

public enum UserSessionStatusEnum {
    NORMAL(0, "正常"),
    DELETED(1, "已删除");
    private final int code;
    private final String description;

    UserSessionStatusEnum(int code, String description) {
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
