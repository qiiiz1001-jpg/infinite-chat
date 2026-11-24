package cn.qingliao.redpacketservice.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RedPacketSendRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long sessionId;
    private Long receiverId;
    private Long senderId;
    private Integer type;
    private Integer sessionType;
    private RedPacketBody body;
    private String clientMessageId;
}
