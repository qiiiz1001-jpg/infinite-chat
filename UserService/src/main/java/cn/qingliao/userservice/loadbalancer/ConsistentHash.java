package cn.qingliao.userservice.loadbalancer;

import org.springframework.cloud.client.ServiceInstance;

import java.util.*;

/** 不可变哈希环；排序节点后构建，保证发现列表顺序变化不会改变结果。 */
public final class ConsistentHash {
    private final NavigableMap<Long, ServiceInstance> nodes = new TreeMap<>();

    public ConsistentHash(List<ServiceInstance> instances) {
        instances.stream()
                .sorted(Comparator.comparing(this::identity))
                .forEach(
                        instance -> {
                            for (int i = 0; i < 160; i++)
                                nodes.put(hash(identity(instance) + "#" + i), instance);
                        });
    }

    public ServiceInstance select(String userId) {
        if (nodes.isEmpty()) throw new IllegalStateException("没有可用的实时节点");
        Map.Entry<Long, ServiceInstance> entry = nodes.ceilingEntry(hash(userId));
        return (entry == null ? nodes.firstEntry() : entry).getValue();
    }

    public String getServer(String userId) {
        return identity(select(userId));
    }

    private String identity(ServiceInstance instance) {
        return instance.getHost() + ":" + instance.getPort();
    }

    private long hash(String text) {
        long value = 2166136261L;
        for (int i = 0; i < text.length(); i++)
            value = ((value ^ text.charAt(i)) * 16777619L) & 0xffffffffL;
        return value;
    }
}
