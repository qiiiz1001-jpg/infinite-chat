package cn.qingliao.userservice.model.dto;

@lombok.Data
public class GroupMemberDTO {
    private Long userId;
    private String nickname;
    private String avatar;
    private Integer role;
}
