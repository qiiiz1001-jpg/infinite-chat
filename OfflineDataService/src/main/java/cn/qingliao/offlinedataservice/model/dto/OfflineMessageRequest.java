package cn.qingliao.offlinedataservice.model.dto;

import lombok.Data;

@Data
public class OfflineMessageRequest {

    private Long userId;

    private Long offlineTime;
}
