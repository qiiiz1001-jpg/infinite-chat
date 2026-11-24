package cn.qingliao.redpacketservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class FinancialKafkaErrorConfig {
    /** 账务失败持续重试，不使用默认“重试耗尽后跳过”策略确认失败账务。 */
    @Bean
    public DefaultErrorHandler financialErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
