package cn.qingliao.offlinedataservice.model.dto;

import lombok.Data;

@Data
public class HistoryMessageRequest {

    private Long sessionId;

    private Long beforeTime;
    private Long beforeMessageId;

    private Integer limit = 20;
}
