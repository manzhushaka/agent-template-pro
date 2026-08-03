package com.manzhushaka.agent.chatapi.support;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.manzhushaka.agent.chatapi")
public class ChatExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ChatExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String, Object>> business(BusinessException exception) {
        return ResponseEntity.status(status(exception.code())).body(Map.of(
                "code", exception.code().name(),
                "message", exception.getMessage(),
                "retryable", false
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> invalid(Exception exception) {
        LOG.error("未预期的请求处理异常", exception);
        return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_FAILED",
                "message", "请求无效",
                "retryable", false
        ));
    }

    private HttpStatus status(ErrorCode code) {
        return switch (code) {
            case RESOURCE_NOT_FOUND, AGENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONVERSATION_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case VALIDATION_FAILED, AGENT_ACTION_OWNERSHIP_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.CONFLICT;
        };
    }
}
