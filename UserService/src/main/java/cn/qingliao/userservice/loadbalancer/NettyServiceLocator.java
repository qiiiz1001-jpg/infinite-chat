package cn.qingliao.userservice.loadbalancer;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NettyServiceLocator {
    private final DiscoveryClient discovery;

    @Value("${chat.netty-fallback-uri:ws://localhost:9101/ws/netty}")
    private String fallback;

    @Value("${spring.cloud.nacos.discovery.enabled:false}")
    private boolean discoveryEnabled;

    /** Nacos 注册的是 HTTP 端口，WebSocket 地址须读取明确的元数据。 */
    public String getServiceInstance(String userId) {
        if (!discoveryEnabled) return fallback;
        List<ServiceInstance> instances = discovery.getInstances("RealTimeService");
        if (instances.isEmpty()) throw new IllegalStateException("实时服务尚未注册或不可用");
        ServiceInstance instance = new UrlHashLoadBalancer().select(instances, userId);
        String host = instance.getMetadata().getOrDefault("ws-host", instance.getHost());
        String port = instance.getMetadata().getOrDefault("ws-port", "9101");
        return "ws://" + host + ":" + port + "/ws/netty";
    }
}
