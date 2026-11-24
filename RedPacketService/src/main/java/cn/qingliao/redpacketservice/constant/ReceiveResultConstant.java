package cn.qingliao.redpacketservice.constant;

public final class ReceiveResultConstant {

    public static final int ALREADY_RECEIVED = -1;

    public static final int EMPTY_POOL = -2;

    public static final int COMPLETION_FLAG_NOT_COMPLETED = 0;

    public static final int COMPLETION_FLAG_COMPLETED = 1;

    public static boolean isCompleted(int completedFlag) {
        return completedFlag == COMPLETION_FLAG_COMPLETED;
    }

    private ReceiveResultConstant() {
        // 私有构造函数，防止实例化
    }

    public static final int NOT_FOUND = -3;

    public static final int NO_PERMISSION = -4;

    public static final int REASON_SENDER_SELF_RECEIVE = 1;

    public static final int REASON_NOT_THE_RECEIVER = 2;

    public static final int REASON_NOT_IN_CONVERSATION = 3;

    public static cn.qingliao.common.enums.ValidationError toValidationError(int reason) {
        return switch (reason) {
            case REASON_SENDER_SELF_RECEIVE ->
                    cn.qingliao.common.enums.ValidationError.REDPACKET_SENDER_CANNOT_RECEIVE;
            case REASON_NOT_THE_RECEIVER ->
                    cn.qingliao.common.enums.ValidationError.REDPACKET_NOT_THE_RECEIVER;
            default -> cn.qingliao.common.enums.ValidationError.REDPACKET_NOT_IN_CONVERSATION;
        };
    }
}
