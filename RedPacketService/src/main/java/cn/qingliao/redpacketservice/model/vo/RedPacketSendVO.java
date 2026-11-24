package cn.qingliao.redpacketservice.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RedPacketSendVO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Long redPacketId;
    private Long messageId;
}
