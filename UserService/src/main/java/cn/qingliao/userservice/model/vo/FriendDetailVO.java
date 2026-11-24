package cn.qingliao.userservice.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class FriendDetailVO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String userId;
    private String nickname;
    private String avatar;
    private String email;
    private String phone;
    private String signature;
    private Integer gender;
    private String sessionId;
    private Integer status;
}
