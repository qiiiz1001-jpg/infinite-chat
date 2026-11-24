package cn.qingliao.offlinedataservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"cn.qingliao.offlinedataservice", "cn.qingliao.common"})
@EnableFeignClients(basePackages = "cn.qingliao.offlinedataservice.client")
public class OfflineDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfflineDataServiceApplication.class, args);
    }
}
