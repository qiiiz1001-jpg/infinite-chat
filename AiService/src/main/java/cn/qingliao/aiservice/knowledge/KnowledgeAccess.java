package cn.qingliao.aiservice.knowledge;

import cn.qingliao.aiservice.client.SessionAccessClient;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgeAccess {
    private final SessionAccessClient sessions;

    public record Context(long userId, long sessionId, boolean personal) {}

    /** 调用失败直接拒绝，不把查不到权限降级为公共知识库。 */
    public Context context(long user, long session) {
        if (user <= 0 || session <= 0) throw denied();
        var result = sessions.knowledgeAccess(user, session);
        if (result == null || !result.accessible()) throw denied();
        return new Context(user, session, result.assistant());
    }

    public long space(long user, String scope, Long session) {
        if (user <= 0) throw denied();
        if ("PERSONAL".equals(scope)) {
            if (session != null && session != 0) throw invalid("个人资料不接受会话ID");
            return 0;
        }
        if (!"SESSION".equals(scope) || session == null) throw invalid("请选择个人或会话知识库");
        context(user, session);
        return session;
    }

    public static BusinessException denied() {
        return new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有该资料或会话的访问权限");
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, message);
    }
}
