package cn.qingliao.realtimeservice.utils;

import cn.hutool.core.lang.Snowflake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeDynamicUtil {
    private final Snowflake snowflake;

    /** 用构造器读取配置，避免 @Value 注入 static 字段失效；多实例必须使用不同节点号。 */
    public SnowflakeDynamicUtil(
            @Value("${snowflake.worker-id:2}") long workerId,
            @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        snowflake = cn.hutool.core.util.IdUtil.getSnowflake(workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
