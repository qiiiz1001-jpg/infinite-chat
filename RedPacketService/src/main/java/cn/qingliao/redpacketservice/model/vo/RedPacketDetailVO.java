package cn.qingliao.redpacketservice.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class RedPacketDetailVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long redPacketId;
    private Long senderId;
    private String senderNickname;
    private String senderAvatar;
    private Long sessionId;
    private Integer sessionType;
    private String redPacketWrapperText;
    private Integer redPacketType;
    private BigDecimal totalAmount;
    private Integer totalCount;
    private Integer receivedCount;
    private BigDecimal receivedAmount;
    private Integer status;
    private Date createdTime;
    private List<RedPacketReceiveVO> receiveRecords;
}
