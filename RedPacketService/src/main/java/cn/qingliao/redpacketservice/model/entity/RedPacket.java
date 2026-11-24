package cn.qingliao.redpacketservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("red_packet")
public class RedPacket implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId private Long redPacketId;
    private Long senderId;
    private Long sessionId;
    private Integer sessionType;
    private String redPacketWrapperText;
    private Integer redPacketType;
    private Long totalAmount;
    private Integer totalCount;
    private Integer status;
    private Date createdTime;
    private Date updatedTime;
}
