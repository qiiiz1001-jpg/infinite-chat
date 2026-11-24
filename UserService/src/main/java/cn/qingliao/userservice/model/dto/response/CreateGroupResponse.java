package cn.qingliao.userservice.model.dto.response;

import java.io.Serializable;
import java.util.List;

@lombok.Data
public class CreateGroupResponse implements Serializable {
    @java.io.Serial private static final long serialVersionUID = 1L;
    private Long creatorId;
    private Long sessionId;
    private String sessionName;
    private Integer sessionType;
    private String avatar;
    private List<Long> failedMemberIds;
    private List<Long> memberIds;
}
