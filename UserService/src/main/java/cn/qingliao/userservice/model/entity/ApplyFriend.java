package cn.qingliao.userservice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("apply_friend")
@Accessors(chain = true)
public class ApplyFriend {
    @TableId(value = "apply_friend_id", type = IdType.ASSIGN_ID)
    private Long applyFriendId;

    @TableField("sender_id")
    private Long senderId;

    @TableField("receiver_id")
    private Long receiverId;

    @TableField("message")
    private String message;

    @TableField("status")
    private Integer status;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;
}
