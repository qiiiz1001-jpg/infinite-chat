package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ModifyFriendApplicationResponse implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String userId;
    private String sessionId;
    private Integer sessionType;
    private String sessionName;
    private String avatar;
}
