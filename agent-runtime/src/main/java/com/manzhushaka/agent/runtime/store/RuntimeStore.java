package com.manzhushaka.agent.runtime.store;

import com.manzhushaka.agent.runtime.chat.ChatMessage;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.chat.PendingAction;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.runtime.task.TaskTransition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable boundary for visitor-owned runtime facts. */
public interface RuntimeStore {
    Conversation createConversation(String visitorId);
    Optional<Conversation> findConversation(String visitorId, String conversationId);
    List<Conversation> listConversations(String visitorId);
    Optional<Conversation> findConversationForAdministration(String conversationId);
    List<Conversation> listConversationsForAdministration();
    long appendMessage(MessageAppend message);
    default long appendMessage(String conversationId, String role, String content, String eventType) {
        return appendMessage(new MessageAppend(conversationId, role, content, eventType, null, null));
    }
    List<ChatMessage> messages(String visitorId, String conversationId);
    PendingAction savePending(PendingAction pendingAction);
    Optional<PendingAction> findPending(String visitorId, String pendingId);
    void removePending(String pendingId);
    AgentTask saveTask(AgentTask task);
    Optional<AgentTask> findTask(String visitorId, String taskId);
    List<AgentTask> listTasks();
    Optional<AgentTask> decideConfirmation(
            String visitorId,
            String taskId,
            int expectedConfirmationVersion,
            long expectedTaskVersion,
            String expectedSnapshotHash,
            ConfirmationDecision decision,
            String requestId,
            Instant decidedAt,
            Instant executionLeaseUntil
    );
    Optional<AgentTask> transitionTask(
            String visitorId,
            String taskId,
            TaskStatus expectedStatus,
            long expectedTaskVersion,
            TaskStatus targetStatus,
            TaskTransition transition
    );
    List<AgentTask> findRecoverableTasks(Instant now, int limit);
    List<OutboxRecord> claimOutbox(String owner, Instant now, Instant leaseUntil, int limit);
    boolean markOutboxPublished(String outboxId, String owner, Instant publishedAt);
    boolean rescheduleOutbox(
            String outboxId,
            String owner,
            Instant availableAt,
            String errorCode,
            int maxAttempts
    );
    ToolExecutionRecord saveToolExecution(ToolExecutionRecord execution);
    List<ToolExecutionRecord> toolExecutions(String visitorId, String taskId);
    void saveAudit(AuditRecord auditRecord);
    List<AuditRecord> audits(String visitorId, String taskId);
    void saveGraphCheckpoint(GraphCheckpointRecord checkpoint);
    List<GraphCheckpointRecord> graphCheckpoints(String graphThreadId);
    void deleteGraphCheckpoints(String graphThreadId);
    void saveEvent(StreamEvent event);
    List<StreamEvent> events(String visitorId, String conversationId, long afterSequence, int limit);
    void saveRouteDecision(RouteDecisionRecord decision);
    Optional<RouteDecisionRecord> latestRoute(String visitorId, String conversationId);
    Optional<PendingAction> findActivePending(String visitorId, String conversationId);
    List<AgentTask> findActiveTasks(String visitorId, String conversationId);
    boolean updateConversationRoute(
            String visitorId,
            String conversationId,
            long expectedVersion,
            String targetAgentCode
    );
    List<TimelineItem> timeline(String visitorId, String conversationId, long afterSequence, int limit);
    RouteMetrics routeMetrics(String agentCode);
    default void audit(String visitorId, String taskId, String type) {
        saveAudit(new AuditRecord(
                "aud_" + java.util.UUID.randomUUID(), visitorId, taskId, null, type,
                "SYSTEM", java.util.Map.of(), Instant.now()
        ));
    }
}
