package cn.qingliao.redpacketservice.constant;

public final class BalanceLogConstant {
    private BalanceLogConstant() {
        // 私有构造函数，防止实例化
    }

    // ==================== 余额变动类型常量 ====================
    public static final int TYPE_SEND = 0;
    public static final int TYPE_RECEIVE = 1;
    public static final int TYPE_REFUND = 2;
}
