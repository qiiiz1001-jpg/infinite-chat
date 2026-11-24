package cn.qingliao.aiservice.guardrail;

import cn.qingliao.common.common.*;

import dev.langchain4j.guardrail.InputGuardrailException;

import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@org.springframework.core.annotation.Order(-10)
public class AiGuardrailExceptionHandler {
    @ExceptionHandler(InputGuardrailException.class)
    public BaseResponse<?> blocked(InputGuardrailException error) {
        return ResultUtils.error(ErrorCode.SENSITIVE_WORD_ERROR);
    }
}
