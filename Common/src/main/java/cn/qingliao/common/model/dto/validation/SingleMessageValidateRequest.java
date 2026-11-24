package cn.qingliao.common.model.dto.validation;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SingleMessageValidateRequest implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Long senderId;
    private Long receiverId;
    private Long sessionId;

    public static SingleMessageValidateRequest of(Long senderId, Long receiverId, Long sessionId) {
        SingleMessageValidateRequest request = new SingleMessageValidateRequest();
        request.setSenderId(senderId);
        request.setReceiverId(receiverId);
        request.setSessionId(sessionId);
        return request;
    }
}
