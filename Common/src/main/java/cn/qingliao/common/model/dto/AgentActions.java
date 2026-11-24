package cn.qingliao.common.model.dto;

import lombok.Data;

/** Agent 与 IM 的业务协议；身份由登录态或绑定身份的内部客户端提供。 */
public final class AgentActions {
    private AgentActions() {}

    public record Draft(
            String requestKey, String kind, Long sessionId, String content, Long executeAt) {}

    public record Edit(int version, String content, Long executeAt) {}

    public record Decision(int version) {}

    public record SessionOption(Long sessionId, String name, int type, Long receiverId) {
        public SessionOption(Long sessionId, String name, int type) {
            this(sessionId, name, type, null);
        }
    }

    @Data
    public static class Action {
        private Long actionId;
        private Long ownerId;
        private Long sessionId;
        private String requestKey;
        private String kind;
        private String content;
        private Long executeAt;
        private String status;
        private int version;
        private Long messageId;
        private Long messageTime;
        private boolean storeSent;
        private boolean pushSent;
        private int attempts;
        private Long nextAttemptAt;
        private Long leaseUntil;
        private String leaseToken;
        private String lastError;
        private Long createdAt;
        private Long updatedAt;
    }
}
