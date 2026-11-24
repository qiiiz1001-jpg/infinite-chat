package cn.qingliao.redpacketservice.util;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 所有金额以分计算；每份为 1 分至 200 元，随机分配不使用无界重试循环。 */
public final class RedPacketAlgorithm {
    private RedPacketAlgorithm() {}

    private static void validate(long amount, int count) {
        if (count < 1 || amount < count || amount > count * 20000L)
            throw new IllegalArgumentException("红包数量或金额超出范围");
    }

    public static List<Long> allocateNormalRedPacket(long amount, int count) {
        validate(amount, count);
        List<Long> result = new ArrayList<>(count);
        long base = amount / count, remainder = amount % count;
        for (int i = 0; i < count; i++) result.add(base + (i < remainder ? 1 : 0));
        return result;
    }

    public static List<Long> allocateRandomRedPacket(long amount, int count) {
        validate(amount, count);
        List<Long> result = new ArrayList<>(count);
        long remaining = amount;
        for (int left = count; left > 1; left--) {
            long lower = Math.max(1, remaining - (left - 1) * 20000L);
            long upper =
                    Math.max(
                            lower,
                            Math.min(
                                    Math.min(20000L, remaining - (left - 1)),
                                    2 * remaining / left));
            long value = ThreadLocalRandom.current().nextLong(lower, upper + 1);
            result.add(value);
            remaining -= value;
        }
        result.add(remaining);
        Collections.shuffle(result);
        return result;
    }
}
