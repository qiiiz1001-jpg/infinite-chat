package cn.qingliao.common.security;

import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.exception.BusinessException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Set;

@Aspect
@Component
public class ActorGuard {
    private static final Set<String> ACTOR_FIELDS =
            Set.of("userId", "senderId", "creatorId", "inviterId", "operatorId");

    @Around("@within(cn.qingliao.common.security.RequireActor)")
    public Object verify(ProceedingJoinPoint point) throws Throwable {
        if (CurrentUser.internal()) return point.proceed();
        String expected = CurrentUser.id().toString();
        String[] names = ((MethodSignature) point.getSignature()).getParameterNames();
        Object[] arguments = point.getArgs();
        for (int i = 0; i < arguments.length; i++) {
            Object value = arguments[i];
            if (value == null) continue;
            if (ACTOR_FIELDS.contains(names[i])) check(expected, value);
            if (value.getClass().getName().startsWith("cn.qingliao.")) {
                for (Field field : value.getClass().getDeclaredFields()) {
                    if (ACTOR_FIELDS.contains(field.getName())) {
                        field.setAccessible(true);
                        Object supplied = field.get(value);
                        if (supplied != null) check(expected, supplied);
                    }
                }
            }
        }
        return point.proceed();
    }

    private void check(String expected, Object supplied) {
        if (!expected.equals(supplied.toString()))
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "操作人身份与登录用户不一致");
    }
}
