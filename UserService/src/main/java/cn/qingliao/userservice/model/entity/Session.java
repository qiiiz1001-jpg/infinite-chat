package cn.qingliao.userservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@TableName(value = "session")
@Data
public class Session implements Serializable {

    @TableId private Long sessionId;

    private String name;

    private Integer type;

    private Integer status;

    private Date createdTime;

    private Date updatedTime;

    private String avatar;

    @TableField(exist = false)
    @Serial
    private static final long serialVersionUID = 1L;
}
