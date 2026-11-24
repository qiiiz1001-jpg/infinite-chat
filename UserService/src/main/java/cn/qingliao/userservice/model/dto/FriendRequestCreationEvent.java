package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class FriendRequestCreationEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long applyFriendId;
    private Long createTime;
    private Long expireTime;
}
