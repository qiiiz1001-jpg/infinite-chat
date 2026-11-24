package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class NewGroupSessionNotificationDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private String sessionName;
    private String avatar;
    private Long creatorId;
    private Integer membersCount;
}
