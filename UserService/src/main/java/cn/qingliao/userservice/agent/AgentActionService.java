package cn.qingliao.userservice.agent;

import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.model.dto.AgentActions.*;
import cn.qingliao.common.utils.SnowflakeUtil;
import cn.qingliao.userservice.model.entity.Session;
import cn.qingliao.userservice.model.entity.UserSession;
import cn.qingliao.userservice.service.SessionService;
import cn.qingliao.userservice.service.UserSessionService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class AgentActionService {
    private final AgentActionRepository repo;
    private final SessionService sessions;
    private final UserSessionService members;
    private final SessionSendAccess access;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentActionService(
            AgentActionRepository repo,
            SessionService sessions,
            UserSessionService members,
            SessionSendAccess access) {
        this(repo, sessions, members, access, Clock.systemUTC());
    }

    AgentActionService(
            AgentActionRepository repo,
            SessionService sessions,
            UserSessionService members,
            SessionSendAccess access,
            Clock clock) {
        this.repo = repo;
        this.sessions = sessions;
        this.members = members;
        this.access = access;
        this.clock = clock;
    }

    public List<SessionOption> sessions(long owner, String keyword) {
        String term = normalized(keyword);
        if (term.length() > 100) throw invalid("会话关键词过长");
        var visible =
                members
                        .list(
                                new LambdaQueryWrapper<UserSession>()
                                        .eq(UserSession::getUserId, owner)
                                        .eq(UserSession::getStatus, 0))
                        .stream()
                        .map(m -> sessions.getById(m.getSessionId()))
                        .filter(Objects::nonNull)
                        .filter(s -> Integer.valueOf(0).equals(s.getStatus()))
                        .map(s -> sessionOption(s, owner))
                        .distinct()
                        .toList();
        if (term.isEmpty()) return visible.stream().limit(50).toList();
        var exact =
                visible.stream()
                        .filter(
                                s ->
                                        s.sessionId().toString().equals(term)
                                                || normalized(s.name()).equals(term))
                        .limit(50)
                        .toList();
        if (!exact.isEmpty()) return exact;
        var aliases =
                visible.stream()
                        .filter(
                                s ->
                                        s.type() == 0
                                                && normalized(s.name())
                                                        .replaceFirst("^(演示)?用户", "")
                                                        .equals(term.replaceFirst("^(演示)?用户", "")))
                        .limit(50)
                        .toList();
        if (!aliases.isEmpty()) return aliases;
        return visible.stream().filter(s -> normalized(s.name()).contains(term)).limit(50).toList();
    }

    private static String normalized(String value) {
        return value == null
                ? ""
                : Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .replaceAll("[\\p{Z}\\s]+", "")
                        .toLowerCase(Locale.ROOT);
    }

    private SessionOption sessionOption(Session session, long owner) {
        long id = session.getSessionId();
        int type = session.getType();
        String name = Objects.toString(session.getName(), "会话 " + id);
        if (type == 0 || type == 2) {
            var peers =
                    repo.jdbc()
                            .query(
                                    "SELECT m.user_id, u.nickname FROM user_session m LEFT JOIN"
                                        + " `user` u ON u.user_id=m.user_id WHERE m.session_id=?"
                                        + " AND m.user_id<>? AND m.status=0",
                                    (row, index) ->
                                            new SessionOption(
                                                    id,
                                                    type == 0
                                                            ? Objects.toString(
                                                                    row.getString("nickname"), name)
                                                            : name,
                                                    type,
                                                    row.getLong("user_id")),
                                    id,
                                    owner);
            if (peers.size() == 1) return peers.get(0);
        }
        return new SessionOption(id, name, type);
    }

    /** 保存草稿而不发送；重复的请求键必须绑定完全相同的操作。 */
    public Action draft(long owner, Draft draft) {
        if (draft == null
                || draft.sessionId() == null
                || draft.sessionId() <= 0
                || draft.requestKey() == null
                || !draft.requestKey().matches("[A-Za-z0-9_-]{1,100}")
                || !("MESSAGE".equals(draft.kind())
                        || "REMINDER".equals(draft.kind())
                        || "EMAIL".equals(draft.kind()))) throw invalid("草稿参数不完整");
        String content = content(draft.kind(), draft.content());
        Action old = repo.byKey(owner, draft.requestKey());
        if (old != null) {
            if (!Objects.equals(old.getSessionId(), draft.sessionId())
                    || !old.getKind().equals(draft.kind())
                    || !old.getContent().equals(content)
                    || !Objects.equals(old.getExecuteAt(), draft.executeAt()))
                throw invalid("请求键已被其他内容使用");
            return old;
        }
        validateTime(draft.kind(), draft.executeAt());
        Action a = new Action();
        a.setActionId(SnowflakeUtil.nextId());
        a.setMessageId(SnowflakeUtil.nextId());
        a.setOwnerId(owner);
        a.setSessionId(draft.sessionId());
        a.setRequestKey(draft.requestKey());
        a.setKind(draft.kind());
        a.setContent(content);
        a.setExecuteAt(draft.executeAt());
        a.setCreatedAt(clock.millis());
        authorize(a);
        try {
            repo.insert(a);
        } catch (DuplicateKeyException e) {
            if (repo.byKey(owner, draft.requestKey()) == null) throw e;
            return draft(owner, draft);
        }
        return owned(owner, a.getActionId());
    }

    public Action owned(long owner, long id) {
        Action a = repo.find(id);
        if (a == null || !Objects.equals(a.getOwnerId(), owner))
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务不存在或无权访问");
        return a;
    }

    public List<Action> list(long owner) {
        return repo.list(owner);
    }

    /** 只有用户 HTTP 确认入口可调用；模型不持有确认工具。 */
    public List<Action> reminders(long owner) {
        return repo.reminders(owner);
    }

    public Action confirm(long owner, long id, int version) {
        Action a = owned(owner, id);
        if (a.getVersion() != version) throw invalid("草稿版本已变化，请重新查看");
        if (!"DRAFT".equals(a.getStatus())) {
            if (List.of("PENDING", "RUNNING", "SENT").contains(a.getStatus())) return a;
            throw invalid("当前任务不能确认");
        }
        validateTime(a.getKind(), a.getExecuteAt());
        authorize(a);
        repo.jdbc()
                .update(
                        "UPDATE agent_action SET status='PENDING',next_attempt_at=?,updated_at=?"
                            + " WHERE action_id=? AND owner_id=? AND status='DRAFT' AND version=?",
                        a.getExecuteAt() == null ? clock.millis() : a.getExecuteAt(),
                        clock.millis(),
                        id,
                        owner,
                        version);
        return owned(owner, id);
    }

    public Action edit(long owner, long id, Edit edit) {
        if (edit == null) throw invalid("缺少修改内容");
        Action a = owned(owner, id);
        String body = content(a.getKind(), edit.content());
        validateTime(a.getKind(), edit.executeAt());
        int changed =
                repo.jdbc()
                        .update(
                                "UPDATE agent_action SET"
                                    + " content=?,execute_at=?,status='DRAFT',version=version+1,updated_at=?"
                                    + " WHERE action_id=? AND owner_id=? AND version=? AND"
                                    + " message_time IS NULL AND status IN ('DRAFT','PENDING')",
                                body,
                                edit.executeAt(),
                                clock.millis(),
                                id,
                                owner,
                                edit.version());
        if (changed != 1) throw invalid("任务已开始执行或版本变化，无法修改");
        return owned(owner, id);
    }

    public Action cancel(long owner, long id, int version) {
        Action a = owned(owner, id);
        if ("CANCELLED".equals(a.getStatus()) && a.getVersion() == version + 1) return a;
        int changed =
                repo.jdbc()
                        .update(
                                "UPDATE agent_action SET"
                                    + " status='CANCELLED',version=version+1,updated_at=? WHERE"
                                    + " action_id=? AND owner_id=? AND version=? AND message_time"
                                    + " IS NULL AND status IN ('DRAFT','PENDING')",
                                clock.millis(),
                                id,
                                owner,
                                version);
        if (changed != 1) throw invalid("任务已执行、正在执行或版本变化；不能撤回已发送消息");
        return owned(owner, id);
    }

    public Action retry(long owner, long id, int version) {
        Action a = owned(owner, id);
        authorize(a);
        if ("EMAIL".equals(a.getKind())) throw invalid("请核对邮箱后重新创建待确认邮件");
        int changed =
                repo.jdbc()
                        .update(
                                "UPDATE agent_action SET"
                                    + " status='PENDING',attempts=0,next_attempt_at=?,version=version+1,updated_at=?"
                                    + " WHERE action_id=? AND owner_id=? AND version=? AND"
                                    + " status='FAILED'",
                                clock.millis(),
                                clock.millis(),
                                id,
                                owner,
                                version);
        if (changed != 1) throw invalid("只有失败任务可以重试");
        return owned(owner, id);
    }

    /** 到期时重新读取会话状态、成员与拉黑规则；不能只相信创建时的授权。 */
    public void authorize(Action a) {
        Session s = sessions.getById(a.getSessionId());
        if (s == null
                || (isPersonal(a)
                        ? !Integer.valueOf(2).equals(s.getType())
                        : !List.of(0, 1).contains(s.getType())))
            throw invalid("邮件和个人提醒必须属于本人助手，消息发送必须选择单聊或群聊");
        if (!access.canSend(a.getOwnerId(), s.getSessionId(), s.getType(), receiver(a)))
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "当前没有向该会话发送的权限");
    }

    public Long receiver(Action a) {
        Session s = sessions.getById(a.getSessionId());
        if (s == null) throw invalid("会话不存在");
        if (Integer.valueOf(1).equals(s.getType())) return null;
        var peers =
                members
                        .list(
                                new LambdaQueryWrapper<UserSession>()
                                        .eq(UserSession::getSessionId, s.getSessionId())
                                        .eq(UserSession::getStatus, 0))
                        .stream()
                        .map(UserSession::getUserId)
                        .filter(id -> !id.equals(a.getOwnerId()))
                        .toList();
        if (peers.size() != 1) throw invalid("无法确定唯一接收者");
        if (isPersonal(a) && !cn.qingliao.common.constant.CommonConstant.AI_ID.equals(peers.get(0)))
            throw invalid("提醒目标不是本人助手会话");
        return peers.get(0);
    }

    private void validateTime(String kind, Long at) {
        if ("REMINDER".equals(kind) && at == null) throw invalid("提醒必须指定时间");
        if (at != null && (at <= clock.millis() || at > clock.millis() + 366L * 86400000L))
            throw invalid("时间必须在未来一年内，请核对日期和时区");
    }

    private boolean isPersonal(Action action) {
        return "REMINDER".equals(action.getKind()) || "EMAIL".equals(action.getKind());
    }

    private String content(String kind, String value) {
        if (value == null || value.isBlank() || value.length() > 4000)
            throw invalid("正文不能为空且最多4000字符");
        String content = value.strip();
        if ("EMAIL".equals(kind)) {
            String[] parts = content.split("\n", 3);
            if (parts.length != 3
                    || parts[0].length() > 254
                    || !parts[0].matches(
                            "[A-Za-z0-9.!#$%&'*+/=?^_{}|~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")
                    || parts[1].isBlank()
                    || parts[1].length() > 160
                    || parts[1].contains("\r")
                    || parts[2].isBlank()) {
                throw invalid("请提供一个有效的收件邮箱、主题和正文");
            }
        }
        return content;
    }

    private BusinessException invalid(String text) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, text);
    }
}
