package cn.qingliao.common.model.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private Long sessionId;
    private Long referenceSessionId;
    private Long userId;
    private String prompt;
}
