package cn.qingliao.common.model.dto.validation;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class GroupMemberCountResponse implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Long sessionId;
    private Integer memberCount;

    public static GroupMemberCountResponse of(Long sessionId, Integer memberCount) {
        GroupMemberCountResponse response = new GroupMemberCountResponse();
        response.setSessionId(sessionId);
        response.setMemberCount(memberCount);
        return response;
    }
}
