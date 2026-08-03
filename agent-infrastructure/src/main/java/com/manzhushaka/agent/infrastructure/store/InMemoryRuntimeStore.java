package com.manzhushaka.agent.infrastructure.store;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.chat.ChatMessage;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.chat.PendingAction;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.store.MessageAppend;
import com.manzhushaka.agent.runtime.store.AuditRecord;
import com.manzhushaka.agent.runtime.store.GraphCheckpointRecord;
import com.manzhushaka.agent.runtime.store.OutboxRecord;
import com.manzhushaka.agent.runtime.store.OutboxStatus;
import com.manzhushaka.agent.runtime.store.RouteDecisionRecord;
import com.manzhushaka.agent.runtime.store.RouteMetrics;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.store.TimelineItem;
import com.manzhushaka.agent.runtime.store.ToolExecutionRecord;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.runtime.task.TaskTransition;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Local/demo store. The JDBC profile replaces this bean with a MySQL implementation. */
@Repository
@Primary
@Profile("!runtime-jdbc")
public class InMemoryRuntimeStore implements RuntimeStore {
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> messages = new ConcurrentHashMap<>();
    private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> taskIdsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, List<StreamEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, List<RouteDecisionRecord>> routes = new ConcurrentHashMap<>();
    private final Map<String, OutboxRecord> outbox = new ConcurrentHashMap<>();
    private final Map<String, ToolExecutionRecord> toolExecutions = new ConcurrentHashMap<>();
    private final Map<String, List<GraphCheckpointRecord>> graphCheckpoints = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final List<AuditRecord> audit = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public Conversation createConversation(String visitorId) {
        String id = "cnv_" + UUID.randomUUID();
        Instant now = Instant.now();
        Conversation conversation = new Conversation(id, visitorId, "gth_" + UUID.randomUUID(), "新会话", now, now);
        conversations.put(id, conversation);
        messages.put(id, java.util.Collections.synchronizedList(new ArrayList<>()));
        events.put(id, java.util.Collections.synchronizedList(new ArrayList<>()));
        routes.put(id, java.util.Collections.synchronizedList(new ArrayList<>()));
        sequences.put(id, new AtomicLong());
        return conversation;
    }

    @Override
    public Optional<Conversation> findConversation(String visitorId, String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId))
                .filter(conversation -> conversation.visitorId().equals(visitorId));
    }

    @Override
    public List<Conversation> listConversations(String visitorId) {
        return conversations.values().stream()
                .filter(conversation -> conversation.visitorId().equals(visitorId))
                .sorted(Comparator.comparing(Conversation::lastMessageAt).reversed())
                .toList();
    }

    @Override
    public Optional<Conversation> findConversationForAdministration(String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public List<Conversation> listConversationsForAdministration() {
        return conversations.values().stream()
                .sorted(Comparator.comparing(Conversation::lastMessageAt).reversed())
                .toList();
    }

    @Override
    public long appendMessage(MessageAppend message) {
        Instant now = Instant.now();
        long sequence = sequence(message.conversationId()).incrementAndGet();
        messages.computeIfAbsent(message.conversationId(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new ChatMessage(
                        sequence,
                        message.role(),
                        message.content(),
                        message.eventType(),
                        message.agentCode(),
                        message.actionCode(),
                        now
                ));
        conversations.computeIfPresent(message.conversationId(), (ignored, conversation) -> new Conversation(
                conversation.id(),
                conversation.visitorId(),
                conversation.graphThreadId(),
                conversation.title(),
                conversation.activeAgentCode(),
                conversation.routingVersion(),
                conversation.createdAt(),
                now
        ));
        return sequence;
    }

    @Override
    public List<ChatMessage> messages(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return List.copyOf(messages.getOrDefault(conversationId, List.of()));
    }

    @Override
    public PendingAction savePending(PendingAction pendingAction) {
        pendingActions.put(pendingAction.id(), pendingAction);
        return pendingAction;
    }

    @Override
    public Optional<PendingAction> findPending(String visitorId, String pendingId) {
        return Optional.ofNullable(pendingActions.get(pendingId))
                .filter(pendingAction -> findConversation(visitorId, pendingAction.conversationId()).isPresent())
                .filter(pendingAction -> pendingAction.expiresAt().isAfter(Instant.now()));
    }

    @Override
    public void removePending(String pendingId) {
        pendingActions.remove(pendingId);
    }

    @Override
    public AgentTask saveTask(AgentTask task) {
        String key = task.actionCode() + ':' + task.idempotencyKey();
        String existingId = taskIdsByIdempotencyKey.putIfAbsent(key, task.id());
        if (existingId != null) {
            return tasks.get(existingId);
        }
        tasks.put(task.id(), task);
        enqueueTaskState(task);
        return task;
    }

    @Override
    public Optional<AgentTask> findTask(String visitorId, String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).filter(task -> task.visitorId().equals(visitorId));
    }

    @Override
    public List<AgentTask> listTasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(AgentTask::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<AgentTask> decideConfirmation(
            String visitorId,
            String taskId,
            int expectedConfirmationVersion,
            long expectedTaskVersion,
            String expectedSnapshotHash,
            ConfirmationDecision decision,
            String requestId,
            Instant decidedAt,
            Instant executionLeaseUntil
    ) {
        AgentTask task = tasks.get(taskId);
        if (task == null || !task.visitorId().equals(visitorId)) {
            return Optional.empty();
        }
        synchronized (task) {
            boolean expired = task.confirmationExpiresAt() == null
                    || !task.confirmationExpiresAt().isAfter(decidedAt);
            if (task.status() != TaskStatus.WAITING_CONFIRMATION
                    || task.confirmationVersion() != expectedConfirmationVersion
                    || task.version() != expectedTaskVersion
                    || !java.util.Objects.equals(task.confirmationSnapshotHash(), expectedSnapshotHash)
                    || (decision == ConfirmationDecision.EXPIRED ? !expired : expired)) {
                return Optional.empty();
            }
            TaskTransition transition = decision == ConfirmationDecision.CONFIRMED
                    ? new TaskTransition(null, null, null, executionLeaseUntil, null, 0)
                    : TaskTransition.none();
            task.applyTransition(decision.targetStatus(), transition);
            enqueueTaskState(task);
            return Optional.of(task);
        }
    }

    @Override
    public Optional<AgentTask> transitionTask(
            String visitorId,
            String taskId,
            TaskStatus expectedStatus,
            long expectedTaskVersion,
            TaskStatus targetStatus,
            TaskTransition transition
    ) {
        AgentTask task = tasks.get(taskId);
        if (task == null || !task.visitorId().equals(visitorId)) {
            return Optional.empty();
        }
        synchronized (task) {
            if (task.status() != expectedStatus
                    || task.version() != expectedTaskVersion
                    || !expectedStatus.canTransitionTo(targetStatus)) {
                return Optional.empty();
            }
            task.applyTransition(targetStatus, transition);
            enqueueTaskState(task);
            return Optional.of(task);
        }
    }

    @Override
    public List<AgentTask> findRecoverableTasks(Instant now, int limit) {
        return tasks.values().stream()
                .filter(task -> task.status() == TaskStatus.DISPATCHED
                        && task.executionLeaseUntil() != null
                        && !task.executionLeaseUntil().isAfter(now)
                        || (task.status() == TaskStatus.WAITING_EXTERNAL_RESULT || task.status() == TaskStatus.UNKNOWN)
                        && task.nextRecoveryAt() != null
                        && !task.nextRecoveryAt().isAfter(now))
                .sorted(Comparator.comparing(AgentTask::updatedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public List<OutboxRecord> claimOutbox(String owner, Instant now, Instant leaseUntil, int limit) {
        synchronized (outbox) {
            List<OutboxRecord> claimed = outbox.values().stream()
                    .filter(record -> (record.status() == OutboxStatus.PENDING
                            && (record.availableAt() == null || !record.availableAt().isAfter(now)))
                            || (record.status() == OutboxStatus.PROCESSING
                            && record.leaseUntil() != null && !record.leaseUntil().isAfter(now)))
                    .sorted(Comparator.comparing(OutboxRecord::createdAt))
                    .limit(limit)
                    .map(record -> new OutboxRecord(
                            record.id(), record.aggregateType(), record.aggregateId(), record.eventType(),
                            record.payload(), OutboxStatus.PROCESSING, record.attemptCount(), record.availableAt(),
                            owner, leaseUntil, record.lastError(), record.createdAt()
                    ))
                    .toList();
            claimed.forEach(record -> outbox.put(record.id(), record));
            return claimed;
        }
    }

    @Override
    public boolean markOutboxPublished(String outboxId, String owner, Instant publishedAt) {
        synchronized (outbox) {
            OutboxRecord current = outbox.get(outboxId);
            if (current == null || current.status() != OutboxStatus.PROCESSING
                    || !java.util.Objects.equals(current.leaseOwner(), owner)) {
                return false;
            }
            outbox.put(outboxId, new OutboxRecord(
                    current.id(), current.aggregateType(), current.aggregateId(), current.eventType(), current.payload(),
                    OutboxStatus.PUBLISHED, current.attemptCount(), current.availableAt(), null, null,
                    current.lastError(), current.createdAt()
            ));
            return true;
        }
    }

    @Override
    public boolean rescheduleOutbox(
            String outboxId,
            String owner,
            Instant availableAt,
            String errorCode,
            int maxAttempts
    ) {
        synchronized (outbox) {
            OutboxRecord current = outbox.get(outboxId);
            if (current == null || current.status() != OutboxStatus.PROCESSING
                    || !java.util.Objects.equals(current.leaseOwner(), owner)) {
                return false;
            }
            int attempts = current.attemptCount() + 1;
            OutboxStatus status = attempts >= maxAttempts ? OutboxStatus.DEAD : OutboxStatus.PENDING;
            outbox.put(outboxId, new OutboxRecord(
                    current.id(), current.aggregateType(), current.aggregateId(), current.eventType(), current.payload(),
                    status, attempts, availableAt, null, null, errorCode, current.createdAt()
            ));
            return true;
        }
    }

    @Override
    public ToolExecutionRecord saveToolExecution(ToolExecutionRecord execution) {
        toolExecutions.put(execution.id(), execution);
        return execution;
    }

    @Override
    public List<ToolExecutionRecord> toolExecutions(String visitorId, String taskId) {
        AgentTask task = findTask(visitorId, taskId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
        return toolExecutions.values().stream()
                .filter(execution -> task.id().equals(execution.taskId()))
                .sorted(Comparator.comparing(ToolExecutionRecord::startedAt))
                .toList();
    }

    @Override
    public void saveAudit(AuditRecord auditRecord) {
        audit.add(auditRecord);
    }

    @Override
    public List<AuditRecord> audits(String visitorId, String taskId) {
        findTask(visitorId, taskId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
        synchronized (audit) {
            return audit.stream()
                    .filter(record -> taskId.equals(record.taskId()))
                    .sorted(Comparator.comparing(AuditRecord::createdAt))
                    .toList();
        }
    }

    @Override
    public void saveGraphCheckpoint(GraphCheckpointRecord checkpoint) {
        synchronized (graphCheckpoints) {
            List<GraphCheckpointRecord> records = new ArrayList<>(
                    graphCheckpoints.getOrDefault(checkpoint.graphThreadId(), List.of())
            );
            records.removeIf(record -> record.checkpointId().equals(checkpoint.checkpointId()));
            records.add(checkpoint);
            records.sort(Comparator.comparing(GraphCheckpointRecord::createdAt).reversed());
            graphCheckpoints.put(checkpoint.graphThreadId(), List.copyOf(records));
        }
    }

    @Override
    public List<GraphCheckpointRecord> graphCheckpoints(String graphThreadId) {
        return graphCheckpoints.getOrDefault(graphThreadId, List.of());
    }

    @Override
    public void deleteGraphCheckpoints(String graphThreadId) {
        graphCheckpoints.remove(graphThreadId);
    }

    @Override
    public void saveEvent(StreamEvent event) {
        events.computeIfAbsent(event.conversationId(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(event);
        enqueue("CONVERSATION", event.conversationId(), event.type(), Map.of(
                "type", event.type(),
                "conversationId", event.conversationId(),
                "requestId", event.requestId(),
                "sequence", event.sequence(),
                "timestamp", event.timestamp().toString(),
                "payload", event.payload()
        ));
    }

    @Override
    public List<StreamEvent> events(String visitorId, String conversationId, long afterSequence, int limit) {
        requireConversation(visitorId, conversationId);
        return events.getOrDefault(conversationId, List.of()).stream()
                .filter(event -> event.sequence() > afterSequence)
                .sorted(Comparator.comparingLong(StreamEvent::sequence))
                .limit(limit)
                .toList();
    }

    @Override
    public void saveRouteDecision(RouteDecisionRecord decision) {
        routes.computeIfAbsent(decision.conversationId(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(decision);
    }

    @Override
    public Optional<RouteDecisionRecord> latestRoute(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return routes.getOrDefault(conversationId, List.of()).stream()
                .max(Comparator.comparing(RouteDecisionRecord::createdAt)
                        .thenComparingInt(RouteDecisionRecord::routeSequence));
    }

    @Override
    public Optional<PendingAction> findActivePending(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return pendingActions.values().stream()
                .filter(pending -> pending.conversationId().equals(conversationId))
                .filter(pending -> pending.expiresAt().isAfter(Instant.now()))
                .findFirst();
    }

    @Override
    public List<AgentTask> findActiveTasks(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return tasks.values().stream()
                .filter(task -> task.conversationId().equals(conversationId))
                .filter(task -> task.status() == TaskStatus.WAITING_CONFIRMATION
                        || task.status() == TaskStatus.WAITING_EXTERNAL_RESULT
                        || task.status() == TaskStatus.DISPATCHED)
                .sorted(Comparator.comparing(AgentTask::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean updateConversationRoute(
            String visitorId,
            String conversationId,
            long expectedVersion,
            String targetAgentCode
    ) {
        Conversation current = findConversation(visitorId, conversationId).orElse(null);
        if (current == null) {
            return false;
        }
        synchronized (conversations) {
            Conversation latest = conversations.get(conversationId);
            if (latest.routingVersion() != expectedVersion) {
                return false;
            }
            conversations.put(conversationId, new Conversation(
                    latest.id(),
                    latest.visitorId(),
                    latest.graphThreadId(),
                    latest.title(),
                    targetAgentCode,
                    latest.routingVersion() + 1,
                    latest.createdAt(),
                    latest.lastMessageAt()
            ));
            return true;
        }
    }

    @Override
    public List<TimelineItem> timeline(
            String visitorId,
            String conversationId,
            long afterSequence,
            int limit
    ) {
        requireConversation(visitorId, conversationId);
        Map<Long, TimelineItem> merged = new LinkedHashMap<>();
        messages.getOrDefault(conversationId, List.of()).stream()
                .filter(message -> message.sequence() > afterSequence)
                .forEach(message -> merged.put(message.sequence(), new TimelineItem(
                        message.sequence(),
                        "MESSAGE",
                        message.role(),
                        message.content(),
                        message.eventType(),
                        null,
                        message.agentCode(),
                        message.actionCode(),
                        message.createdAt(),
                        Map.of()
                )));
        events.getOrDefault(conversationId, List.of()).stream()
                .filter(event -> event.sequence() > afterSequence)
                .filter(event -> !"message.final".equals(event.type()))
                .forEach(event -> merged.put(event.sequence(), eventItem(event)));
        return merged.values().stream()
                .sorted(Comparator.comparingLong(TimelineItem::sequence))
                .limit(limit)
                .toList();
    }

    @Override
    public RouteMetrics routeMetrics(String agentCode) {
        List<RouteDecisionRecord> decisions = routes.values().stream().flatMap(List::stream).toList();
        long total = decisions.stream().filter(route -> agentCode.equals(route.targetAgentCode())).count();
        long ambiguous = decisions.stream()
                .filter(route -> route.routeType() == com.manzhushaka.agent.runtime.routing.RouteType.CLARIFICATION_REQUIRED)
                .filter(route -> route.candidateAgentCodes().contains(agentCode))
                .count();
        long failure = decisions.stream()
                .filter(route -> route.routeType() == com.manzhushaka.agent.runtime.routing.RouteType.UNSUPPORTED)
                .filter(route -> route.candidateAgentCodes().contains(agentCode))
                .count();
        long switches = decisions.stream()
                .filter(route -> agentCode.equals(route.targetAgentCode()))
                .filter(route -> route.sourceAgentCode() != null
                        && !ChatOrchestratorCodes.COORDINATOR.equals(route.sourceAgentCode())
                        && !agentCode.equals(route.sourceAgentCode()))
                .count();
        return new RouteMetrics(total, ambiguous, failure, switches);
    }

    private AtomicLong sequence(String conversationId) {
        AtomicLong sequence = sequences.get(conversationId);
        if (sequence == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在");
        }
        return sequence;
    }

    private void enqueueTaskState(AgentTask task) {
        enqueue("TASK", task.id(), "task.status", Map.of(
                "taskId", task.id(),
                "visitorId", task.visitorId(),
                "conversationId", task.conversationId(),
                "actionCode", task.actionCode(),
                "status", task.status().name(),
                "version", task.version()
        ));
    }

    private void enqueue(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        Instant now = Instant.now();
        OutboxRecord record = new OutboxRecord(
                "obx_" + UUID.randomUUID(), aggregateType, aggregateId, eventType, payload,
                OutboxStatus.PENDING, 0, now, null, null, null, now
        );
        outbox.put(record.id(), record);
    }

    private TimelineItem eventItem(StreamEvent event) {
        Map<String, Object> agent = event.payload().get("agent") instanceof Map<?, ?> value
                ? value.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()),
                        Map.Entry::getValue
                ))
                : Map.of();
        return new TimelineItem(
                event.sequence(),
                "EVENT",
                "SYSTEM",
                "",
                event.type(),
                event.requestId(),
                value(agent.get("code")),
                value(event.payload().get("actionCode")),
                event.timestamp(),
                event.payload()
        );
    }

    private String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class ChatOrchestratorCodes {
        private static final String COORDINATOR = "group-assistant";

        private ChatOrchestratorCodes() {
        }
    }

    private void requireConversation(String visitorId, String conversationId) {
        findConversation(visitorId, conversationId).orElseThrow(
                () -> new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "会话不存在或不属于当前访客")
        );
    }
}
