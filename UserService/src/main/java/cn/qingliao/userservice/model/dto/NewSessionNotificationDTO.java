package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class NewSessionNotificationDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private String sessionName;
    private String avatar;
}
