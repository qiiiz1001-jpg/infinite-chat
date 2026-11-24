package cn.qingliao.redpacketservice.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class RedPacketBasicVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long redPacketId;
    private Integer redPacketType;
    private BigDecimal totalAmount;
    private Integer totalCount;
    private Integer receivedCount;
    private BigDecimal receivedAmount;
    private Integer status;
    private Date createdTime;
}
