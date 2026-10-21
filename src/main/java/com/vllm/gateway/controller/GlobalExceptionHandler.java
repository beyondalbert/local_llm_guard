package com.vllm.gateway.controller;

import com.vllm.gateway.util.R;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        return R.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        return R.error(500, e.getMessage() == null ? "服务器内部错误" : e.getMessage());
    }
}
