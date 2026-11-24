package cn.qingliao.aiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.cloud.openfeign.EnableFeignClients(
        basePackages = "cn.qingliao.aiservice.client")
@SpringBootApplication(scanBasePackages = {"cn.qingliao.aiservice", "cn.qingliao.common"})
@org.springframework.scheduling.annotation.EnableScheduling
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
