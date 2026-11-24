package cn.qingliao.userservice.loadbalancer;

import org.springframework.cloud.client.ServiceInstance;

import java.util.List;

public class UrlHashLoadBalancer {
    public ServiceInstance select(List<ServiceInstance> instances, String userId) {
        return new ConsistentHash(instances).select(userId);
    }
}
