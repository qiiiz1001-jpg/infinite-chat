package cn.qingliao.common.model.dto.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageValidateResponse implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private boolean allowed;
    private String rejectReason;
    private Integer errorCode;

    public static MessageValidateResponse allow() {
        return MessageValidateResponse.builder().allowed(true).build();
    }

    public static MessageValidateResponse reject(Integer errorCode, String rejectReason) {
        return MessageValidateResponse.builder()
                .allowed(false)
                .errorCode(errorCode)
                .rejectReason(rejectReason)
                .build();
    }
}
