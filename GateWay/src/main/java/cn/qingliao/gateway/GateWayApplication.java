package cn.qingliao.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(
        exclude = {DataSourceAutoConfiguration.class},
        scanBasePackages = "cn.qingliao.gateway")
public class GateWayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateWayApplication.class, args);
    }
}
