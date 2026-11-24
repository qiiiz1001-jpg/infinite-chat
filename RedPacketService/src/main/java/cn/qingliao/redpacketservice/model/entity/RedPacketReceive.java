package cn.qingliao.redpacketservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("red_packet_receive")
public class RedPacketReceive implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId private Long redPacketReceiveId;
    private Long redPacketId;
    private Long receiverId;
    private Long amount;
    private Date receivedAt;
    private Date createdTime;
    private Date updatedTime;
}
