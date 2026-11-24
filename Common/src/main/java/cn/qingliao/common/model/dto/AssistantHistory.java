package cn.qingliao.common.model.dto;

import java.util.List;

public final class AssistantHistory {
    private AssistantHistory() {}

    public record Query(long userId, long sessionId, Long beforeTime, Long beforeMessageId) {}

    public record Entry(String sender, long sentAt, String text) {}

    public record Page(List<Entry> messages, Long nextBeforeTime, String nextBeforeMessageId) {}
}
