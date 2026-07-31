package com.manzhushaka.agent.chatapi.support;

import com.manzhushaka.agent.common.error.BusinessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class ChatExceptionHandler {
    @ExceptionHandler(BusinessException.class) @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,Object> business(BusinessException ex) { return Map.of("code", ex.code().name(), "message", ex.getMessage(), "retryable", false); }
    @ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,Object> invalid(Exception ex) { return Map.of("code", "VALIDATION_FAILED", "message", "请求无效", "retryable", false); }
}
