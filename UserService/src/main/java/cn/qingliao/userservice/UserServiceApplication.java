package cn.qingliao.userservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.cloud.openfeign.EnableFeignClients(
        basePackages = "cn.qingliao.userservice.client")
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(scanBasePackages = {"cn.qingliao.userservice", "cn.qingliao.common"})
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
