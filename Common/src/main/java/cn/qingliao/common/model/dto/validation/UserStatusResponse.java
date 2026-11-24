package cn.qingliao.common.model.dto.validation;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserStatusResponse implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Long userId;
    private Integer state;
    private String stateName;
}
