package cn.qingliao.userservice.model.dto.response;

@lombok.Data
public class InviteGroupResponse {
    private java.util.List<Long> successIds;
    private java.util.List<Long> failedIds;
}
