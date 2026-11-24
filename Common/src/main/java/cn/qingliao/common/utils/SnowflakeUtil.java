package cn.qingliao.common.utils;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import cn.qingliao.common.constant.SnowflakeConstant;

@org.springframework.stereotype.Component
public class SnowflakeUtil {
    private static volatile Snowflake SNOWFLAKE =
            IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);

    /** 不同服务默认分配不同节点号；同一服务的多个实例仍必须各自配置唯一 worker-id。 */
    public SnowflakeUtil(
            @org.springframework.beans.factory.annotation.Value(
                            "${spring.application.name:UserService}")
                    String application,
            @org.springframework.beans.factory.annotation.Value("${snowflake.worker-id:-1}")
                    long workerId,
            @org.springframework.beans.factory.annotation.Value("${snowflake.datacenter-id:1}")
                    long datacenterId) {
        long fallback =
                switch (application) {
                    case "RealTimeService" -> 2;
                    case "RedPacketService" -> 3;
                    case "OfflineDataService" -> 4;
                    case "AiService" -> 5;
                    default -> 1;
                };
        SNOWFLAKE = IdUtil.getSnowflake(workerId < 0 ? fallback : workerId, datacenterId);
    }

    public static long nextId() {
        return SNOWFLAKE.nextId();
    }
}
