package cn.qingliao.userservice.model.dto.request;

import jakarta.validation.constraints.NotEmpty;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class ModifyFriendApplicationRequest implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @NotEmpty(message = "接收者ID列表不能为空")
    private List<String> receiveuserIds;
}
