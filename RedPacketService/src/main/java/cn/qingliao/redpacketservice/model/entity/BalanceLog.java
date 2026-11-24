package cn.qingliao.redpacketservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("balance_log")
public class BalanceLog implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId private Long balanceLogId;
    private Long userId;
    private Long amount;
    private Integer type;
    private Long relatedId;
    private Date createdTime;
    private Date updatedTime;
}
