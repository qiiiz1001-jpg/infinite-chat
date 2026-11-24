package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class ApplyFriendDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String userId;
    private String nickname;
    private String avatar;
    private String msg;
    private Integer status;
    private LocalDateTime time;
    private Integer isReceiver;
}
