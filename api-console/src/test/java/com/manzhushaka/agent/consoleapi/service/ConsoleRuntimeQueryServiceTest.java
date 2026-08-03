package com.manzhushaka.agent.consoleapi.service;

import com.manzhushaka.agent.consoleapi.dto.ConsoleConversationResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleTaskDetailResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleTaskResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.store.AuditRecord;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.store.ToolExecutionRecord;
import com.manzhushaka.agent.runtime.store.ToolExecutionStatus;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsoleRuntimeQueryServiceTest {
    private RuntimeStore store;
    private ConsoleRuntimeQueryService service;

    @BeforeEach
    void setUp() {
        store = mock(RuntimeStore.class);
        service = new ConsoleRuntimeQueryService(
                store,
                mock(ChatOrchestrator.class),
                new MockEnvironment()
        );
    }

    @Test
    void paginatesAndFiltersConversationsWithoutExposingVisitorId() {
        Instant now = Instant.parse("2026-08-02T10:00:00Z");
        when(store.listConversationsForAdministration()).thenReturn(List.of(
                conversation("conv_new", "visitor-sensitive", "退款查询", now.plusSeconds(2)),
                conversation("conv_old", "visitor-other", "订单查询", now)
        ));

        PageResponse<ConsoleConversationResponse> result = service.conversations(1, 1, "退款");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("conv_new");
            assertThat(item.visitorRef()).startsWith("visitor_");
            assertThat(item.visitorRef()).doesNotContain("sensitive");
        });
    }

    @Test
    void aggregatesTaskDetailUsingVisitorOwnedStoreQueries() {
        Instant now = Instant.parse("2026-08-02T10:00:00Z");
        AgentTask task = new AgentTask(
                "task_1", "visitor-sensitive", "conv_1", "demo", "demo.order.commit", "idem_1",
                Map.of("phone", "13800000000"), TaskStatus.WAITING_EXTERNAL_RESULT,
                1, 3, "hash", null, "external_1", "等待结果", null,
                null, now.plusSeconds(30), 1, now, now
        );
        ToolExecutionRecord execution = new ToolExecutionRecord(
                "tool_1", task.id(), task.conversationId(), "demo.order.commit", "v1",
                ToolExecutionStatus.SUCCEEDED, Map.of("fieldCount", 1), Map.of("accepted", true),
                "external_1", "trace_1", now, now.plusSeconds(1)
        );
        AuditRecord audit = new AuditRecord(
                "audit_1", task.visitorId(), task.id(), "request_1", "TASK_DISPATCHED",
                "SYSTEM", Map.of("status", "DISPATCHED"), now
        );
        when(store.listTasks()).thenReturn(List.of(task));
        when(store.toolExecutions(task.visitorId(), task.id())).thenReturn(List.of(execution));
        when(store.audits(task.visitorId(), task.id())).thenReturn(List.of(audit));

        ConsoleTaskDetailResponse result = service.task(task.id());

        assertThat(result.task().visitorRef()).doesNotContain("sensitive");
        assertThat(result.task().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("input", "idempotencyKey", "visitorId");
        assertThat(result.toolExecutions()).singleElement().extracting(value -> value.id()).isEqualTo("tool_1");
        assertThat(result.audits()).singleElement().extracting(value -> value.id()).isEqualTo("audit_1");
        verify(store).toolExecutions("visitor-sensitive", "task_1");
        verify(store).audits("visitor-sensitive", "task_1");
    }

    @Test
    void filtersTasksByStatusAndActionCode() {
        Instant now = Instant.parse("2026-08-02T10:00:00Z");
        AgentTask waiting = task("task_waiting", "demo.order.commit", TaskStatus.WAITING_EXTERNAL_RESULT, now);
        AgentTask completed = task("task_done", "demo.order.query", TaskStatus.SUCCEEDED, now.minusSeconds(10));
        when(store.listTasks()).thenReturn(List.of(completed, waiting));

        PageResponse<ConsoleTaskResponse> result = service.tasks(
                1, 20, "WAITING_EXTERNAL_RESULT", "order.commit", null
        );

        assertThat(result.items()).singleElement().extracting(ConsoleTaskResponse::id)
                .isEqualTo("task_waiting");
    }

    private Conversation conversation(String id, String visitorId, String title, Instant updatedAt) {
        return new Conversation(id, visitorId, "graph_" + id, title, "demo", 1, updatedAt, updatedAt);
    }

    private AgentTask task(String id, String actionCode, TaskStatus status, Instant now) {
        return new AgentTask(
                id, "visitor", "conversation", "demo", actionCode, "idem_" + id,
                Map.of(), status, 0, 0, null, null, now, now
        );
    }
}
