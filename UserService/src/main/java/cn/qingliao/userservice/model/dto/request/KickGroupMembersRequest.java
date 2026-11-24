package cn.qingliao.userservice.model.dto.request;

@lombok.Data
public class KickGroupMembersRequest {
    private Long sessionId;
    private Long operatorId;
    private java.util.List<Long> memberIds;
}
