package cn.qingliao.common.aop;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class LogInterceptor {
    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object record(ProceedingJoinPoint point) throws Throwable {
        long start = System.nanoTime();
        try {
            return point.proceed();
        } finally {
            log.info(
                    "request method={}, elapsedMs={}",
                    point.getSignature().toShortString(),
                    (System.nanoTime() - start) / 1000000);
        }
    }
}
