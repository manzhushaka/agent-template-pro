package com.manzhushaka.agent.chatapi.controller;

import com.manzhushaka.agent.chatapi.support.ChatExceptionHandler;
import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.identity.VisitorCookie;
import com.manzhushaka.agent.runtime.identity.VisitorIdentityService;
import com.manzhushaka.agent.runtime.recovery.TaskRecoveryService;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ChatControllerRecoveryTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-03T08:00:00Z");

    private ChatOrchestrator orchestrator;
    private VisitorIdentityService identity;
    private TaskRecoveryService recoveryService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ChatOrchestrator.class);
        identity = mock(VisitorIdentityService.class);
        recoveryService = mock(TaskRecoveryService.class);
        when(identity.resolve("signed-visitor-cookie")).thenReturn("visitor-a");
        when(identity.cookie("visitor-a")).thenReturn(new VisitorCookie("agent_visitor", "refreshed-cookie", 60));
        when(orchestrator.agentDescriptor("demo")).thenReturn(Optional.empty());
        client = WebTestClient.bindToController(new ChatController(orchestrator, identity, recoveryService))
                .controllerAdvice(new ChatExceptionHandler())
                .build();
    }

    @Test
    void recoversTaskOwnedByResolvedVisitor() {
        when(recoveryService.recover(eq("visitor-a"), eq("task-1"), eq("client-request-1"), any(Instant.class)))
                .thenReturn(task(TaskStatus.SUCCEEDED, "external-1"));

        recover("task-1", "client-request-1")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("task-1")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.externalRef").isEqualTo("external-1");

        verify(recoveryService).recover(eq("visitor-a"), eq("task-1"), eq("client-request-1"), any(Instant.class));
        verify(orchestrator).agentDescriptor("demo");
        verifyNoMoreInteractions(orchestrator);
    }

    @Test
    void hidesTaskOwnedByAnotherVisitor() {
        when(recoveryService.recover(eq("visitor-a"), eq("task-b"), eq("client-request-2"), any(Instant.class)))
                .thenThrow(new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在或不属于当前访客"
                ));

        recover("task-b", "client-request-2")
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
                .jsonPath("$.message").isEqualTo("任务不存在或不属于当前访客");

        verify(recoveryService).recover(eq("visitor-a"), eq("task-b"), eq("client-request-2"), any(Instant.class));
        verifyNoMoreInteractions(orchestrator);
    }

    @Test
    void returnsUnchangedTaskWhenRuntimeSaysStatusIsNotRecoverable() {
        when(recoveryService.recover(eq("visitor-a"), eq("task-1"), eq("client-request-3"), any(Instant.class)))
                .thenReturn(task(TaskStatus.WAITING_CONFIRMATION, null));

        recover("task-1", "client-request-3")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("WAITING_CONFIRMATION")
                .jsonPath("$.externalRef").doesNotExist();

        verify(recoveryService).recover(eq("visitor-a"), eq("task-1"), eq("client-request-3"), any(Instant.class));
        verify(orchestrator).agentDescriptor("demo");
        verifyNoMoreInteractions(orchestrator);
    }

    @Test
    void returnsFailedStatusWhenExternalQueryDeterminesFailure() {
        when(recoveryService.recover(eq("visitor-a"), eq("task-1"), eq("client-request-4"), any(Instant.class)))
                .thenReturn(task(TaskStatus.FAILED, "external-1"));

        recover("task-1", "client-request-4")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FAILED")
                .jsonPath("$.externalRef").isEqualTo("external-1");

        verify(recoveryService).recover(eq("visitor-a"), eq("task-1"), eq("client-request-4"), any(Instant.class));
        verify(orchestrator).agentDescriptor("demo");
        verifyNoMoreInteractions(orchestrator);
    }

    private WebTestClient.ResponseSpec recover(String taskId, String requestId) {
        return client.post()
                .uri("/api/chat/v1/tasks/{id}:recover", taskId)
                .cookie("agent_visitor", "signed-visitor-cookie")
                .header("X-Client-Request-Id", requestId)
                .exchange();
    }

    private AgentTask task(TaskStatus status, String externalRef) {
        return new AgentTask(
                "task-1",
                "visitor-a",
                "conversation-1",
                "demo.order.submit",
                "idempotency-1",
                Map.of(),
                status,
                1,
                2,
                "confirmation-hash",
                externalRef,
                CREATED_AT,
                CREATED_AT
        );
    }
}
