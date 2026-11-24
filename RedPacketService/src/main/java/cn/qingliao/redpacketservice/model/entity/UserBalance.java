package cn.qingliao.redpacketservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("user_balance")
public class UserBalance implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId private Long userId;
    private Long balance;
    private Date createdTime;
    private Date updatedTime;
}
