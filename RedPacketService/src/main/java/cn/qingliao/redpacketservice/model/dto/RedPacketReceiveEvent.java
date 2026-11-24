package cn.qingliao.redpacketservice.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor // JSONUtil.toBean 反序列化依赖无参构造 + setter
@AllArgsConstructor // 配合 @Builder
public class RedPacketReceiveEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long userId;
    private Long redPacketId;
    private Long receivedAmount;
    private Long receiveTime;
}
