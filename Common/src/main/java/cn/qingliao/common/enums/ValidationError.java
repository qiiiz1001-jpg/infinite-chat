package cn.qingliao.common.enums;

import java.util.HashMap;
import java.util.Map;

public enum ValidationError {
    // ===================== 消息校验错误码（91000-91099）=====================
    VALIDATION_FAILED(91001, "消息校验失败"),
    NOT_FRIEND(91002, "对方不是您的好友"),
    BLOCKED_BY_RECEIVER(91003, "您已被对方拉黑"),
    FRIEND_DELETED(91004, "好友关系已删除"),
    NOT_GROUP_MEMBER(91005, "您不是该群成员"),
    SENDER_DISABLED(91006, "发送者账号异常"),
    RECEIVER_DISABLED(91007, "接收者账号异常"),
    SERVICE_UNAVAILABLE(91008, "服务暂时不可用，请稍后重试"),
    // ===================== 红包校验错误码（92000-92099）=====================
    REDPACKET_COUNT_EXCEED_MEMBERS(92001, "红包数量不能超过群成员数量"),
    REDPACKET_SENDER_CANNOT_RECEIVE(92003, "不能领取自己发出的单聊红包"),
    REDPACKET_NOT_THE_RECEIVER(92004, "您不是红包接收者"),
    REDPACKET_NOT_IN_CONVERSATION(92005, "您不在红包发送时的群成员快照中"),
    REDPACKET_SINGLE_ONLY_NORMAL(92002, "单聊只能发送普通红包");
    private final int code;
    private final String message;
    private static final Map<Integer, ValidationError> CODE_MAP;
    private static final Map<String, ValidationError> NAME_MAP;

    static {
        CODE_MAP = new HashMap<>();
        NAME_MAP = new HashMap<>();
        for (ValidationError e : values()) {
            CODE_MAP.put(e.code, e);
            NAME_MAP.put(e.name(), e);
        }
    }

    ValidationError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static ValidationError fromCode(int code) {
        return CODE_MAP.getOrDefault(code, VALIDATION_FAILED);
    }

    public static ValidationError fromName(String name) {
        if (name == null) {
            return VALIDATION_FAILED;
        }
        return NAME_MAP.getOrDefault(name, VALIDATION_FAILED);
    }
}
