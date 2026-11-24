package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class FriendApplicationNotificationDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private String applyUserName;
    private Long applyUserId;
    private String message;
    private String applyFriendAvatar;
}
