package cn.qingliao.redpacketservice;

import static org.junit.jupiter.api.Assertions.*;

import cn.qingliao.redpacketservice.util.RedPacketAlgorithm;

import org.junit.jupiter.api.Test;

import java.util.List;

class RedPacketAlgorithmTest {
    @Test
    void randomAllocationConservesMoneyAndBoundsEveryShare() {
        for (int count : new int[] {1, 2, 10, 100, 500}) {
            for (long amount : new long[] {count, count + 1L, count * 100L, count * 20000L}) {
                for (int attempt = 0; attempt < 20; attempt++)
                    check(RedPacketAlgorithm.allocateRandomRedPacket(amount, count), amount, count);
            }
        }
    }

    @Test
    void normalAllocationSpreadsRemainderByAtMostOneCent() {
        List<Long> result = RedPacketAlgorithm.allocateNormalRedPacket(100, 3);
        check(result, 100, 3);
        assertEquals(
                1L,
                result.stream().mapToLong(Long::longValue).max().orElseThrow()
                        - result.stream().mapToLong(Long::longValue).min().orElseThrow());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RedPacketAlgorithm.allocateRandomRedPacket(1, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> RedPacketAlgorithm.allocateNormalRedPacket(0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RedPacketAlgorithm.allocateRandomRedPacket(20001, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RedPacketAlgorithm.allocateRandomRedPacket(1000, 0));
    }

    @Test
    void supportsOriginalMemberBoundInsteadOfAnExtraFiveHundredShareLimit() {
        check(RedPacketAlgorithm.allocateRandomRedPacket(100100, 1001), 100100, 1001);
        check(RedPacketAlgorithm.allocateNormalRedPacket(100100, 1001), 100100, 1001);
    }

    private void check(List<Long> values, long amount, int count) {
        assertEquals(count, values.size());
        assertEquals(amount, values.stream().mapToLong(Long::longValue).sum());
        assertTrue(values.stream().allMatch(value -> value >= 1 && value <= 20000));
    }
}
