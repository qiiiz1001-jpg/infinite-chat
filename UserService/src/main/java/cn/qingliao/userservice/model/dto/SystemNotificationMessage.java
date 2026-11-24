package cn.qingliao.userservice.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class SystemNotificationMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private String messageId;
    private Long sessionId;
    private Long senderId;
    private Long receiverId;
    private Integer type;
    private Integer sessionType;
    private Long timestamp;
    private Map<String, Object> body;
}
