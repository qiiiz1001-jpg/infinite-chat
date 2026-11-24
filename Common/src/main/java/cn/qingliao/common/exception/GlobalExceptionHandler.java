package cn.qingliao.common.exception;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.common.ResultUtils;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常处理器 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleValidationException(MethodArgumentNotValidException e) {
        java.util.Map<String, String> errors = new java.util.LinkedHashMap<>();
        e.getBindingResult()
                .getAllErrors()
                .forEach(
                        error -> {
                            String field =
                                    error instanceof org.springframework.validation.FieldError item
                                            ? item.getField()
                                            : error.getObjectName();
                            errors.put(field, error.getDefaultMessage());
                        });
        return ResultUtils.error(ErrorCode.INVALID_PARAMETER_ERROR, errors.toString());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public BaseResponse<?> handlerMissingServletRequestParameterException(
            org.springframework.web.bind.MissingServletRequestParameterException error) {
        return ResultUtils.error(ErrorCode.INVALID_PARAMETER_ERROR, "缺少必填参数");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public BaseResponse<?> handleConstraintViolation(ConstraintViolationException e) {
        String message =
                e.getConstraintViolations().stream()
                        .map(ConstraintViolation::getMessage)
                        .findFirst()
                        .orElse("请求参数校验失败");
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }
}
