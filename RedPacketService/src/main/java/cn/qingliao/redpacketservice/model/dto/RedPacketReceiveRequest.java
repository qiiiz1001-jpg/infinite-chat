package cn.qingliao.redpacketservice.model.dto;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

import java.io.Serializable;

@Data
public class RedPacketReceiveRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull private Long userId;
    @NotNull private Long redPacketId;
}
