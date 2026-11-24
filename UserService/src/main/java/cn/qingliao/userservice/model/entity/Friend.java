package cn.qingliao.userservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("friend")
@Accessors(chain = true)
public class Friend {
    @TableField("user_id")
    private Long userId;

    @TableField("friend_id")
    private Long friendId;

    @TableField("status")
    private Integer status;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;
}
