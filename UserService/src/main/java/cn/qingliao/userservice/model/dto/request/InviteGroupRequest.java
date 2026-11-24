package cn.qingliao.userservice.model.dto.request;

@lombok.Data
public class InviteGroupRequest {
    private Long sessionId;
    private Long inviterId;
    private java.util.List<Long> inviteeIds;
}
