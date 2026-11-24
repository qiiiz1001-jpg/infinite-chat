package cn.qingliao.common.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

class JwtContractTest {
    @Test
    void sharedTokenPreservesUserSubject() {
        assertEquals(
                "1001", JwtUtil.parse(JwtUtil.generate("1001", 5, TimeUnit.MINUTES)).getSubject());
    }

    @Test
    void expiredAndMalformedTokensAreRejected() {
        assertNull(JwtUtil.parse(JwtUtil.generate("1001", -5, TimeUnit.MINUTES)));
        assertNull(JwtUtil.parse("broken.token"));
        assertNull(JwtUtil.parse(""));
    }
}
