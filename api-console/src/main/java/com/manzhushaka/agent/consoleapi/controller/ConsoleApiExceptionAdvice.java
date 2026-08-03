package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ConsoleErrorResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.consoleapi.service.ConsoleResourceNotFoundException;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Shared error mapping for evaluation and observability endpoints. */
@RestControllerAdvice(basePackages = "com.manzhushaka.agent.consoleapi.controller")
public class ConsoleApiExceptionAdvice {
    @ExceptionHandler(ConsoleAuthenticationException.class)
    public ResponseEntity<ConsoleErrorResponse> authentication(ConsoleAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse(exception.reason().code(), exception.reason().message()));
    }

    @ExceptionHandler(ControlPlaneAccessDeniedException.class)
    public ResponseEntity<ConsoleErrorResponse> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONSOLE_PERMISSION_DENIED", "当前管理员没有执行此操作的权限。"));
    }

    @ExceptionHandler(ConsoleResourceNotFoundException.class)
    public ResponseEntity<ConsoleErrorResponse> notFound(ConsoleResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONSOLE_RESOURCE_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ConsoleErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONSOLE_EVALUATION_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ConsoleErrorResponse> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONSOLE_EVALUATION_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ConsoleErrorResponse> duplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONSOLE_EVALUATION_CONFLICT",
                        "数据集、评估器或实验的唯一键已存在。"));
    }
}
