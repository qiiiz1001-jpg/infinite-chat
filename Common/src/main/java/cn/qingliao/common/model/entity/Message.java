package cn.qingliao.common.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 与初始化脚本的 message 表对应；真正持久化在原文第 8 章补齐。 */
@Data
@TableName("message")
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;
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
