package cn.qingliao.userservice.loadbalancer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;

import java.util.*;

class ConsistentHashTest {
    private ServiceInstance node(String name, int port) {
        return new DefaultServiceInstance(name, "RealTimeService", "localhost", port, false);
    }

    @Test
    void discoveryOrderDoesNotChangePlacementAndRingAlwaysWraps() {
        List<ServiceInstance> nodes = List.of(node("a", 8102), node("b", 8202), node("c", 8302));
        ConsistentHash first = new ConsistentHash(nodes);
        List<ServiceInstance> reverse = new ArrayList<>(nodes);
        Collections.reverse(reverse);
        ConsistentHash second = new ConsistentHash(reverse);
        for (int i = 0; i < 10000; i++) {
            assertEquals(first.getServer("user-" + i), second.getServer("user-" + i));
        }
    }

    @Test
    void addingNodeOnlyMovesUsersToNewNode() {
        ServiceInstance a = node("a", 8102), b = node("b", 8202), c = node("c", 8302);
        ConsistentHash before = new ConsistentHash(List.of(a, b));
        ConsistentHash after = new ConsistentHash(List.of(a, b, c));
        int moved = 0;
        for (int i = 0; i < 10000; i++) {
            ServiceInstance old = before.select("u" + i), current = after.select("u" + i);
            if (old != current) {
                moved++;
                assertSame(c, current);
            }
        }
        assertTrue(moved > 0 && moved < 8000);
    }

    @Test
    void emptyRegistryFailsExplicitly() {
        assertThrows(IllegalStateException.class, () -> new ConsistentHash(List.of()).select("1"));
    }
}
