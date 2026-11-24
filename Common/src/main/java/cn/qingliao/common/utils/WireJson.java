package cn.qingliao.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** Netty 不经过 MVC 的 Jackson 配置，需要显式使用相同的大整数传输约定。 */
public final class WireJson {
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(
                            new SimpleModule()
                                    .addSerializer(Long.class, ToStringSerializer.instance)
                                    .addSerializer(Long.TYPE, ToStringSerializer.instance));

    private WireJson() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("消息序列化失败", error);
        }
    }

    public static Object read(String value) {
        try {
            return MAPPER.readValue(value, Object.class);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("消息格式错误", error);
        }
    }
}
