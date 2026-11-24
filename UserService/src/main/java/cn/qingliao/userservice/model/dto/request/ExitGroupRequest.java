package cn.qingliao.userservice.model.dto.request;

@lombok.Data
public class ExitGroupRequest {
    private Long sessionId;
    private Long userId;
}
