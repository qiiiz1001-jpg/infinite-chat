package cn.qingliao.common.security;

import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.exception.BusinessException;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 身份来自服务端校验后设置的 request attribute，不能直接信任外部 userId。 */
public final class CurrentUser {
    public static final String USER_ID = "infinitechat.auth.userId";
    public static final String INTERNAL = "infinitechat.auth.internal";

    private CurrentUser() {}

    public static Long id() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            Object value = attributes.getRequest().getAttribute(USER_ID);
            if (value != null) return Long.valueOf(value.toString());
        }
        throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
    }

    public static boolean internal() {
        return RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attributes
                && Boolean.TRUE.equals(attributes.getRequest().getAttribute(INTERNAL));
    }
}
