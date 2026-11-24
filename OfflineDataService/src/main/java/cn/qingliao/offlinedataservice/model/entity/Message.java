package cn.qingliao.offlinedataservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.util.Date;

@TableName(value = "message")
@Data
public class Message {

    @TableId private Long messageId;

    private Long senderId;

    private Long sessionId;

    private Integer type;

    private String content;

    private Long replyId;

    private Integer sessionType;

    private Date createdTime;

    private Date updatedTime;
}
