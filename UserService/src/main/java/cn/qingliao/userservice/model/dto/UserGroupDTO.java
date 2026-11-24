package cn.qingliao.userservice.model.dto;

@lombok.Data
public class UserGroupDTO implements java.io.Serializable {
    @java.io.Serial private static final long serialVersionUID = 1L;
    private Long sessionId;
    private String name;
    private String avatar;
    private Long creatorId;
    private Integer memberCount;
    private Integer role;
    private String createdTime;
    // 兼容此前讲义版本；原文主字段为 name/memberCount。
    private String sessionName;
    private Integer membersCount;
}
