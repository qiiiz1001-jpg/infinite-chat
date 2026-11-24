package cn.qingliao.realtimeservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.cloud.openfeign.EnableFeignClients(
        basePackages = "cn.qingliao.realtimeservice.client")
@SpringBootApplication(scanBasePackages = {"cn.qingliao.realtimeservice", "cn.qingliao.common"})
public class RealTimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealTimeServiceApplication.class, args);
    }
}
