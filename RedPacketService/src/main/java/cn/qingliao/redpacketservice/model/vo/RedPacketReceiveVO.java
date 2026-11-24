package cn.qingliao.redpacketservice.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class RedPacketReceiveVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long receiverId;
    private String receiverNickname;
    private String receiverAvatar;
    private BigDecimal amount;
    private Date receivedAt;
}
