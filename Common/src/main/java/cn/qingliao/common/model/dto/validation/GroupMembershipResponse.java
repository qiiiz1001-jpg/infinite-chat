package cn.qingliao.common.model.dto.validation;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class GroupMembershipResponse implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Long userId;
    private Long sessionId;
    private Boolean isMember;
    private Integer role;
    public static final int ROLE_OWNER = 0;
    public static final int ROLE_ADMIN = 1;
    public static final int ROLE_MEMBER = 2;

    public static GroupMembershipResponse of(
            Long userId, Long sessionId, Boolean isMember, Integer role) {
        GroupMembershipResponse response = new GroupMembershipResponse();
        response.setUserId(userId);
        response.setSessionId(sessionId);
        response.setIsMember(isMember);
        response.setRole(role);
        return response;
    }
}
