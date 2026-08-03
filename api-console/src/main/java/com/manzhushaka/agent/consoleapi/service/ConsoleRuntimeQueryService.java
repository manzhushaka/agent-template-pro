package com.manzhushaka.agent.consoleapi.service;

import com.manzhushaka.agent.consoleapi.dto.ConsoleAuditResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleConversationResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleEventResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleOverviewResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleTaskDetailResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleTaskResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleToolExecutionResponse;
import com.manzhushaka.agent.consoleapi.dto.CursorPageResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.store.AuditRecord;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.store.ToolExecutionRecord;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Service
public class ConsoleRuntimeQueryService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_EVENT_PAGE_SIZE = 199;
    private static final Set<TaskStatus> TERMINAL_STATUSES = EnumSet.of(
            TaskStatus.SUCCEEDED,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED,
            TaskStatus.EXPIRED,
            TaskStatus.MANUAL
    );

    private final RuntimeStore store;
    private final ChatOrchestrator orchestrator;
    private final Environment environment;

    public ConsoleRuntimeQueryService(RuntimeStore store, ChatOrchestrator orchestrator, Environment environment) {
        this.store = store;
        this.orchestrator = orchestrator;
        this.environment = environment;
    }

    public ConsoleOverviewResponse overview() {
        List<Conversation> conversations = store.listConversationsForAdministration();
        List<AgentTask> tasks = store.listTasks();
        long activeTasks = tasks.stream().filter(task -> !TERMINAL_STATUSES.contains(task.status())).count();
        long unknownTasks = tasks.stream()
                .filter(task -> task.status() == TaskStatus.UNKNOWN || task.status() == TaskStatus.MANUAL)
                .count();
        long agentTotal = orchestrator.registeredAgents().size();
        return new ConsoleOverviewResponse(
                agentTotal == 0 ? "DEGRADED" : "READY",
                storageMode(),
                conversations.size(),
                tasks.size(),
                activeTasks,
                unknownTasks,
                agentTotal
        );
    }

    public PageResponse<ConsoleConversationResponse> conversations(
            int requestedPage,
            int requestedSize,
            String query
    ) {
        String normalizedQuery = normalize(query);
        List<ConsoleConversationResponse> values = store.listConversationsForAdministration().stream()
                .filter(conversation -> normalizedQuery.isEmpty()
                        || contains(conversation.id(), normalizedQuery)
                        || contains(conversation.title(), normalizedQuery)
                        || contains(conversation.activeAgentCode(), normalizedQuery))
                .sorted(Comparator.comparing(Conversation::lastMessageAt).reversed())
                .map(this::conversation)
                .toList();
        return page(values, requestedPage, requestedSize);
    }

    public PageResponse<ConsoleTaskResponse> tasks(
            int requestedPage,
            int requestedSize,
            String status,
            String actionCode,
            String query
    ) {
        String normalizedStatus = normalize(status);
        String normalizedAction = normalize(actionCode);
        String normalizedQuery = normalize(query);
        Predicate<AgentTask> predicate = task -> (normalizedStatus.isEmpty()
                || task.status().name().equalsIgnoreCase(normalizedStatus))
                && (normalizedAction.isEmpty() || contains(task.actionCode(), normalizedAction))
                && (normalizedQuery.isEmpty()
                || contains(task.id(), normalizedQuery)
                || contains(task.conversationId(), normalizedQuery)
                || contains(task.externalRef(), normalizedQuery));
        List<ConsoleTaskResponse> values = store.listTasks().stream()
                .filter(predicate)
                .sorted(Comparator.comparing(AgentTask::createdAt).reversed())
                .map(this::task)
                .toList();
        return page(values, requestedPage, requestedSize);
    }

    public ConsoleTaskDetailResponse task(String taskId) {
        AgentTask task = store.listTasks().stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new ConsoleResourceNotFoundException("任务不存在。"));
        List<ConsoleToolExecutionResponse> executions = store.toolExecutions(task.visitorId(), task.id()).stream()
                .map(this::toolExecution)
                .toList();
        List<ConsoleAuditResponse> audits = store.audits(task.visitorId(), task.id()).stream()
                .map(this::audit)
                .toList();
        return new ConsoleTaskDetailResponse(task(task), executions, audits);
    }

    public CursorPageResponse<ConsoleEventResponse> conversationEvents(
            String conversationId,
            long afterSequence,
            int requestedLimit
    ) {
        Conversation conversation = store.findConversationForAdministration(conversationId)
                .orElseThrow(() -> new ConsoleResourceNotFoundException("会话不存在。"));
        int limit = Math.max(1, Math.min(requestedLimit, MAX_EVENT_PAGE_SIZE));
        List<ConsoleEventResponse> events = store.events(
                        conversation.visitorId(), conversation.id(), Math.max(0, afterSequence), limit + 1
                ).stream()
                .map(this::event)
                .toList();
        boolean hasMore = events.size() > limit;
        List<ConsoleEventResponse> page = hasMore ? events.subList(0, limit) : events;
        long nextSequence = page.isEmpty()
                ? Math.max(0, afterSequence)
                : page.get(page.size() - 1).sequence();
        return new CursorPageResponse<>(page, nextSequence, hasMore);
    }

    private ConsoleConversationResponse conversation(Conversation value) {
        return new ConsoleConversationResponse(
                value.id(), visitorRef(value.visitorId()), value.title(), value.activeAgentCode(),
                agentName(value.activeAgentCode()), value.routingVersion(), value.createdAt(), value.lastMessageAt()
        );
    }

    private ConsoleTaskResponse task(AgentTask value) {
        return new ConsoleTaskResponse(
                value.id(), visitorRef(value.visitorId()), value.conversationId(), value.domainCode(),
                agentName(value.domainCode()), value.actionCode(), value.status().name(), value.version(),
                value.confirmationVersion(), value.confirmationExpiresAt(), value.externalRef(),
                value.resultSummary(), value.lastErrorCode(), value.nextRecoveryAt(), value.recoveryAttempts(),
                value.createdAt(), value.updatedAt()
        );
    }

    private ConsoleEventResponse event(StreamEvent value) {
        Map<String, Object> payload = value.payload();
        return new ConsoleEventResponse(
                value.type(), value.conversationId(), value.requestId(), value.sequence(), value.timestamp(),
                string(payload.get("taskId")), string(payload.get("status")), string(payload.get("actionCode")),
                agentCode(payload.get("agent"))
        );
    }

    private ConsoleToolExecutionResponse toolExecution(ToolExecutionRecord value) {
        return new ConsoleToolExecutionResponse(
                value.id(), value.toolCode(), value.toolVersionId(), value.status().name(),
                value.inputSummary(), value.outputSummary(), value.externalRef(), value.traceId(),
                value.startedAt(), value.finishedAt()
        );
    }

    private ConsoleAuditResponse audit(AuditRecord value) {
        return new ConsoleAuditResponse(
                value.id(), value.requestId(), value.eventType(), value.actorType(), value.metadata(), value.createdAt()
        );
    }

    private String storageMode() {
        return environment.acceptsProfiles(Profiles.of("runtime-jdbc")) ? "mysql-jdbc" : "in-memory";
    }

    private String agentName(String code) {
        if (code == null || code.isBlank()) {
            return ChatOrchestrator.COORDINATOR_NAME;
        }
        return orchestrator.agentDescriptor(code)
                .map(descriptor -> descriptor.displayName())
                .orElse(code);
    }

    private static String visitorRef(String visitorId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(visitorId.getBytes(StandardCharsets.UTF_8));
            return "visitor_" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String agentCode(Object value) {
        if (!(value instanceof Map<?, ?> agent)) {
            return null;
        }
        return string(agent.get("code"));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> PageResponse<T> page(List<T> values, int requestedPage, int requestedSize) {
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        long offset = (long) (page - 1) * size;
        int fromIndex = offset >= values.size() ? values.size() : (int) offset;
        int toIndex = Math.min(fromIndex + size, values.size());
        int totalPages = values.isEmpty() ? 0 : (values.size() + size - 1) / size;
        return new PageResponse<>(values.subList(fromIndex, toIndex), page, size, values.size(), totalPages);
    }
}
