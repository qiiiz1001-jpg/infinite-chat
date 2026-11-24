package cn.qingliao.redpacketservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.cloud.openfeign.EnableFeignClients(
        basePackages = "cn.qingliao.redpacketservice.client")
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(scanBasePackages = {"cn.qingliao.redpacketservice", "cn.qingliao.common"})
public class RedPacketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedPacketServiceApplication.class, args);
    }
}
