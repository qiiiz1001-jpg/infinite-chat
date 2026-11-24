package cn.qingliao.redpacketservice.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class ReceiveResultVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Integer status;
    private String message;
    private BigDecimal amount;
}
