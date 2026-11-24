package cn.qingliao.common.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfig {
    /** JavaScript Number 无法精确表示雪花 ID；HTTP 协议中的 Long 统一输出字符串。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longIdsAsStrings() {
        return builder ->
                builder.serializerByType(Long.class, ToStringSerializer.instance)
                        .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
