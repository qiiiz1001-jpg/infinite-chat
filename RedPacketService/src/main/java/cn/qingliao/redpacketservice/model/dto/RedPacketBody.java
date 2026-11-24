package cn.qingliao.redpacketservice.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class RedPacketBody implements Serializable {
    private static final long serialVersionUID = 1L;
    private Integer redPacketType;
    private BigDecimal totalAmount;
    private Integer totalCount;
    private String redPacketWrapperText;
}
