package cn.qingliao.aiservice.tool;

import cn.qingliao.aiservice.client.AgentActionClient;
import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.model.dto.AgentActions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** 每次请求绑定可信用户和助手会话，工具换线程也不从 ThreadLocal 猜身份。 */
public final class ImActionTool {
    private final long actorId;
    private final long sourceSessionId;
    private final AgentActionClient client;
    private final ObjectMapper json;
    private final String invocation = UUID.randomUUID().toString();

    public ImActionTool(
            long actorId, long sourceSessionId, AgentActionClient client, ObjectMapper json) {
        this.actorId = actorId;
        this.sourceSessionId = sourceSessionId;
        this.client = client;
        this.json = json;
    }

    @Tool(
            "查找当前用户有权访问的IM会话。发送前必须用本工具取得真实ID，不能猜测或编造ID。支持大小写与空格差异、演示用户 A/B"
                    + " 简称。只为单聊或群聊代发。多个重名须请用户选择。type=0单聊、1群聊、2助手。")
    public String findImSessions(String keyword) {
        return result(client.sessions(actorId, keyword));
    }

    @Tool("为单聊或群聊创建消息草稿，不会立即发送。返回DRAFT后请用户到操作面板核对并确认；不得声称已经发送。")
    public String prepareSessionMessage(
            String sessionId,
            String content,
            @P(value = "立即发送传空；定时发送传完整ISO日期时间和时区，例如2026-11-21T15:00:00+08:00", required = false)
                    String sendAt) {
        return draft("MESSAGE", Long.parseLong(sessionId), content, time(sendAt));
    }

    @Tool("按明确的收件邮箱、主题和正文保存邮件草稿。用户须在 AI 助手下面核对并确认。")
    public String prepareEmail(
            String recipient,
            String subject,
            String content,
            @P(value = "立即发送时省略；定时发送传ISO日期时间和时区", required = false) String sendAt) {
        if (recipient == null
                || subject == null
                || content == null
                || recipient.matches("(?s).*[\\r\\n].*")
                || subject.matches("(?s).*[\\r\\n].*")) {
            return "请提供一个收件邮箱、单行主题和正文。";
        }
        return draft(
                "EMAIL",
                sourceSessionId,
                recipient.strip() + "\n" + subject.strip() + "\n" + content,
                time(sendAt));
    }

    @Tool("为用户本人安排提醒，已明确内容和时间时直接排期。先核对当前时间，歧义时间先追问。")
    public String preparePersonalReminder(
            String content, @P("完整ISO日期时间及偏移量，例如2026-11-21T15:00:00+08:00") String remindAt) {
        return draft("REMINDER", sourceSessionId, content, time(remindAt));
    }

    @Tool("查询当前用户最近100项消息草稿与提醒，包含ID、版本、时间、状态和失败摘要。修改取消前先查真实ID和版本；SENT只表示消息投递已确认，不表示对方已读。")
    public String listMyActions() {
        return result(client.list(actorId));
    }

    @Tool("按用户明确要求修改尚未执行的任务。修改后变回DRAFT，旧版本失效，需要用户重新确认。时间必须带日期和时区；单纯取消应使用cancelMyAction。")
    public String reviseMyAction(String actionId, int version, String content, String executeAt) {
        return result(
                client.edit(
                        actorId,
                        Long.parseLong(actionId),
                        new Edit(version, content, time(executeAt))));
    }

    @Tool("按用户明确要求取消未执行任务。先查询任务，目标不明确时追问，不能撤回已发送消息。")
    public String cancelMyAction(String actionId, int version) {
        return result(client.cancel(actorId, Long.parseLong(actionId), new Decision(version)));
    }

    private String draft(String kind, long target, String body, Long at) {
        String input = invocation + "|" + kind + "|" + target + "|" + body + "|" + at;
        String key = UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
        var action = new Draft(key, kind, target, body, at);
        return result(
                "REMINDER".equals(kind)
                        ? client.scheduleReminder(actorId, action)
                        : client.draft(actorId, action));
    }

    private Long time(String value) {
        return value == null || value.isBlank()
                ? null
                : OffsetDateTime.parse(value).toInstant().toEpochMilli();
    }

    private String result(BaseResponse<?> response) {
        if (response == null) return "IM服务未返回结果；操作结果未确认，请查询任务，不要声称成功。";
        if (response.getCode() != 200) return "操作未完成：" + response.getMessage();
        var node = json.valueToTree(response.getData());
        if (node instanceof ObjectNode object && response.getData() instanceof Action a) {
            object.put(
                    "executeAtShanghai",
                    a.getExecuteAt() == null
                            ? "用户确认后尽快执行"
                            : Instant.ofEpochMilli(a.getExecuteAt())
                                    .atZone(ZoneId.of("Asia/Shanghai"))
                                    .toString());
            if ("PENDING".equals(a.getStatus()) && "REMINDER".equals(a.getKind())) {
                object.put("notice", "个人提醒已排期，到期显示在消息页的提醒会话。");
            }
            if ("DRAFT".equals(a.getStatus()))
                object.put("notice", "尚未执行。用户需在IM操作面板核对目标、正文、时间并确认。模型无权代替用户确认。");
        }
        return node.toString();
    }
}
