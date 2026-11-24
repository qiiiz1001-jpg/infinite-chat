package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class FriendDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String userId;
    private String nickname;
    private String avatar;
    private Integer status;
    private String signature;
    private String sessionId;
}
