package cn.qingliao.userservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@TableName(value = "user_session")
@Data
public class UserSession implements Serializable {

    @TableId private Long userId;

    private Long sessionId;

    private Integer role;

    private Integer status;

    private Date createdTime;

    private Date updatedTime;

    @TableField(exist = false)
    @Serial
    private static final long serialVersionUID = 1L;
}
