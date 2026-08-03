package com.manzhushaka.agent.infrastructure.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.chat.ChatMessage;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.chat.PendingAction;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.store.AuditRecord;
import com.manzhushaka.agent.runtime.store.GraphCheckpointRecord;
import com.manzhushaka.agent.runtime.store.MessageAppend;
import com.manzhushaka.agent.runtime.store.OutboxRecord;
import com.manzhushaka.agent.runtime.store.OutboxStatus;
import com.manzhushaka.agent.runtime.store.RouteDecisionRecord;
import com.manzhushaka.agent.runtime.store.RouteMetrics;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.store.TimelineItem;
import com.manzhushaka.agent.runtime.store.ToolExecutionRecord;
import com.manzhushaka.agent.runtime.store.ToolExecutionStatus;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.runtime.task.TaskTransition;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL-backed source of truth for Runtime facts. Redis checkpoints may accelerate graph
 * recovery, but no visitor-owned state or confirmation gate is stored there.
 */
@Repository
@Profile("runtime-jdbc")
public class JdbcRuntimeStore implements RuntimeStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcRuntimeStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Conversation createConversation(String visitorId) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT IGNORE INTO agent_visitor "
                        + "(id, visitor_key_hash, status, first_seen_at, last_seen_at, created_at, updated_at) "
                        + "VALUES (?, SHA2(?, 256), 'ACTIVE', ?, ?, ?, ?)",
                visitorId, visitorId, timestamp(now), timestamp(now), timestamp(now), timestamp(now)
        );
        String id = "cnv_" + UUID.randomUUID();
        String graphThreadId = "gth_" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agent_conversation "
                        + "(id, visitor_id, graph_thread_id, title, status, active_agent_code, routing_version, "
                        + "event_sequence, last_message_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', NULL, 0, 0, ?, ?, ?)",
                id, visitorId, graphThreadId, "新会话", timestamp(now), timestamp(now), timestamp(now)
        );
        return new Conversation(id, visitorId, graphThreadId, "新会话", now, now);
    }

    @Override
    public Optional<Conversation> findConversation(String visitorId, String conversationId) {
        return jdbcTemplate.query(
                "SELECT id, visitor_id, graph_thread_id, title, active_agent_code, routing_version, "
                        + "created_at, last_message_at "
                        + "FROM agent_conversation WHERE id = ? AND visitor_id = ?",
                (resultSet, rowNum) -> conversation(resultSet), conversationId, visitorId
        ).stream().findFirst();
    }

    @Override
    public List<Conversation> listConversations(String visitorId) {
        return jdbcTemplate.query(
                "SELECT id, visitor_id, graph_thread_id, title, active_agent_code, routing_version, "
                        + "created_at, last_message_at "
                        + "FROM agent_conversation WHERE visitor_id = ? ORDER BY last_message_at DESC",
                (resultSet, rowNum) -> conversation(resultSet), visitorId
        );
    }

    @Override
    public Optional<Conversation> findConversationForAdministration(String conversationId) {
        return jdbcTemplate.query(
                "SELECT id, visitor_id, graph_thread_id, title, active_agent_code, routing_version, "
                        + "created_at, last_message_at FROM agent_conversation WHERE id = ?",
                (resultSet, rowNum) -> conversation(resultSet), conversationId
        ).stream().findFirst();
    }

    @Override
    public List<Conversation> listConversationsForAdministration() {
        return jdbcTemplate.query(
                "SELECT id, visitor_id, graph_thread_id, title, active_agent_code, routing_version, "
                        + "created_at, last_message_at FROM agent_conversation ORDER BY last_message_at DESC",
                (resultSet, rowNum) -> conversation(resultSet)
        );
    }

    @Override
    @Transactional
    public long appendMessage(MessageAppend message) {
        Instant now = Instant.now();
        Long sequence = jdbcTemplate.queryForObject(
                "SELECT event_sequence FROM agent_conversation WHERE id = ? FOR UPDATE",
                Long.class,
                message.conversationId()
        );
        if (sequence == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在");
        }
        long nextSequence = sequence + 1;
        jdbcTemplate.update(
                "UPDATE agent_conversation SET event_sequence = ?, last_message_at = ?, updated_at = ? WHERE id = ?",
                nextSequence, timestamp(now), timestamp(now), message.conversationId()
        );
        jdbcTemplate.update(
                "INSERT INTO agent_message "
                        + "(id, conversation_id, sequence_no, role, content_ciphertext, content_masked, event_type, "
                        + "agent_code, action_code, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)",
                "msg_" + UUID.randomUUID(), message.conversationId(), nextSequence, message.role(), message.content(),
                message.eventType(), message.agentCode(), message.actionCode(),
                timestamp(now), timestamp(now)
        );
        return nextSequence;
    }

    @Override
    public List<ChatMessage> messages(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return jdbcTemplate.query(
                "SELECT sequence_no, role, content_masked, event_type, agent_code, action_code, created_at "
                        + "FROM agent_message WHERE conversation_id = ? ORDER BY sequence_no",
                (resultSet, rowNum) -> new ChatMessage(
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("role"),
                        resultSet.getString("content_masked"),
                        resultSet.getString("event_type"),
                        resultSet.getString("agent_code"),
                        resultSet.getString("action_code"),
                        instant(resultSet, "created_at")
                ),
                conversationId
        );
    }

    @Override
    @Transactional
    public PendingAction savePending(PendingAction pendingAction) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_pending_action "
                        + "(id, conversation_id, domain_code, action_code, input_json, expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE domain_code = VALUES(domain_code), input_json = CAST(? AS JSON), "
                        + "expires_at = ?, updated_at = ?",
                pendingAction.id(), pendingAction.conversationId(), pendingAction.domainCode(), pendingAction.actionCode(),
                json(pendingAction.input()),
                timestamp(pendingAction.expiresAt()), timestamp(now), timestamp(now), json(pendingAction.input()),
                timestamp(pendingAction.expiresAt()), timestamp(now)
        );
        return pendingAction;
    }

    @Override
    public Optional<PendingAction> findPending(String visitorId, String pendingId) {
        Instant now = Instant.now();
        return jdbcTemplate.query(
                "SELECT pending.id, pending.conversation_id, pending.domain_code, pending.action_code, "
                        + "pending.input_json, pending.expires_at "
                        + "FROM agent_pending_action pending "
                        + "JOIN agent_conversation conversation ON conversation.id = pending.conversation_id "
                        + "WHERE pending.id = ? AND conversation.visitor_id = ? AND pending.expires_at > ?",
                (resultSet, rowNum) -> new PendingAction(
                        resultSet.getString("id"),
                        resultSet.getString("conversation_id"),
                        resultSet.getString("domain_code"),
                        resultSet.getString("action_code"),
                        map(resultSet.getString("input_json")),
                        instant(resultSet, "expires_at")
                ), pendingId, visitorId, timestamp(now)
        ).stream().findFirst();
    }

    @Override
    public void removePending(String pendingId) {
        jdbcTemplate.update("DELETE FROM agent_pending_action WHERE id = ?", pendingId);
    }

    @Override
    @Transactional
    public AgentTask saveTask(AgentTask task) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update(
                    "INSERT INTO agent_task "
                            + "(id, visitor_id, conversation_id, domain_code, action_code, status, external_ref, idempotency_key, "
                            + "input_json, confirmation_version, confirmation_snapshot_hash, confirmation_expires_at, "
                            + "result_summary, last_error_code, execution_lease_until, next_recovery_at, recovery_attempts, "
                            + "version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    task.id(), task.visitorId(), task.conversationId(), task.domainCode(), task.actionCode(),
                    task.status().name(), task.externalRef(), task.idempotencyKey(), json(task.input()),
                    task.confirmationVersion(), task.confirmationSnapshotHash(), timestamp(task.confirmationExpiresAt()),
                    task.resultSummary(), task.lastErrorCode(), timestamp(task.executionLeaseUntil()),
                    timestamp(task.nextRecoveryAt()), task.recoveryAttempts(), task.version(),
                    timestamp(task.createdAt()), timestamp(now)
            );
            if (task.status() == TaskStatus.WAITING_CONFIRMATION) {
                jdbcTemplate.update(
                        "INSERT INTO agent_confirmation "
                                + "(id, task_id, confirmation_version, snapshot_hash, expires_at, decision, decided_at, "
                                + "request_id, confirmed_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?)",
                        "cfm_" + UUID.randomUUID(), task.id(), task.confirmationVersion(), task.confirmationSnapshotHash(),
                        timestamp(task.confirmationExpiresAt()), timestamp(now), timestamp(now)
                );
            }
            enqueueTaskState(task.id(), task.status(), task.version(), now);
            return task;
        } catch (DuplicateKeyException duplicateKeyException) {
            return jdbcTemplate.query(
                    "SELECT * FROM agent_task WHERE action_code = ? AND idempotency_key = ?",
                    (resultSet, rowNum) -> task(resultSet), task.actionCode(), task.idempotencyKey()
            ).stream().findFirst().orElseThrow(() -> duplicateKeyException);
        }
    }

    @Override
    @Transactional
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
        String expiryPredicate = decision == ConfirmationDecision.EXPIRED
                ? "confirmation_expires_at <= ?"
                : "confirmation_expires_at > ?";
        int updated = jdbcTemplate.update(
                "UPDATE agent_task SET status = ?, execution_lease_until = ?, next_recovery_at = NULL, "
                        + "version = version + 1, updated_at = ? WHERE id = ? AND visitor_id = ? "
                        + "AND status = 'WAITING_CONFIRMATION' AND confirmation_version = ? AND version = ? "
                        + "AND confirmation_snapshot_hash = ? AND " + expiryPredicate,
                decision.targetStatus().name(), timestamp(executionLeaseUntil), timestamp(decidedAt), taskId, visitorId,
                expectedConfirmationVersion, expectedTaskVersion, expectedSnapshotHash, timestamp(decidedAt)
        );
        if (updated == 0) {
            return Optional.empty();
        }
        jdbcTemplate.update(
                "UPDATE agent_confirmation SET decision = ?, request_id = ?, decided_at = ?, confirmed_at = ?, "
                        + "updated_at = ? WHERE task_id = ? AND confirmation_version = ? AND decision IS NULL",
                decision.name(), requestId, timestamp(decidedAt),
                decision == ConfirmationDecision.CONFIRMED ? timestamp(decidedAt) : null,
                timestamp(decidedAt), taskId, expectedConfirmationVersion
        );
        enqueueTaskState(taskId, decision.targetStatus(), expectedTaskVersion + 1, decidedAt);
        return findTask(visitorId, taskId);
    }

    @Override
    public Optional<AgentTask> findTask(String visitorId, String taskId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_task WHERE id = ? AND visitor_id = ?",
                (resultSet, rowNum) -> task(resultSet), taskId, visitorId
        ).stream().findFirst();
    }

    @Override
    public List<AgentTask> listTasks() {
        return jdbcTemplate.query(
                "SELECT * FROM agent_task ORDER BY created_at DESC",
                (resultSet, rowNum) -> task(resultSet)
        );
    }

    @Override
    @Transactional
    public Optional<AgentTask> transitionTask(
            String visitorId,
            String taskId,
            TaskStatus expectedStatus,
            long expectedTaskVersion,
            TaskStatus targetStatus,
            TaskTransition transition
    ) {
        if (!expectedStatus.canTransitionTo(targetStatus)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                "UPDATE agent_task SET status = ?, external_ref = COALESCE(?, external_ref), result_summary = ?, "
                        + "last_error_code = ?, execution_lease_until = ?, next_recovery_at = ?, "
                        + "recovery_attempts = recovery_attempts + ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND visitor_id = ? AND status = ? AND version = ?",
                targetStatus.name(), transition.externalRef(), transition.resultSummary(), transition.errorCode(),
                timestamp(transition.executionLeaseUntil()), timestamp(transition.nextRecoveryAt()),
                transition.recoveryAttemptDelta(), timestamp(now), taskId, visitorId,
                expectedStatus.name(), expectedTaskVersion
        );
        if (updated == 0) {
            return Optional.empty();
        }
        enqueueTaskState(taskId, targetStatus, expectedTaskVersion + 1, now);
        return findTask(visitorId, taskId);
    }

    @Override
    public List<AgentTask> findRecoverableTasks(Instant now, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_task WHERE "
                        + "(status = 'DISPATCHED' AND execution_lease_until IS NOT NULL AND execution_lease_until <= ?) "
                        + "OR (status IN ('WAITING_EXTERNAL_RESULT', 'UNKNOWN') "
                        + "AND next_recovery_at IS NOT NULL AND next_recovery_at <= ?) "
                        + "ORDER BY updated_at LIMIT ?",
                (resultSet, rowNum) -> task(resultSet), timestamp(now), timestamp(now), limit
        );
    }

    @Override
    @Transactional
    public List<OutboxRecord> claimOutbox(String owner, Instant now, Instant leaseUntil, int limit) {
        List<OutboxRecord> candidates = jdbcTemplate.query(
                "SELECT * FROM agent_task_outbox WHERE "
                        + "(status = 'PENDING' AND (available_at IS NULL OR available_at <= ?)) "
                        + "OR (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until <= ?) "
                        + "ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (resultSet, rowNum) -> outbox(resultSet), timestamp(now), timestamp(now), limit
        );
        return candidates.stream().filter(record -> jdbcTemplate.update(
                "UPDATE agent_task_outbox SET status = 'PROCESSING', lease_owner = ?, lease_until = ?, updated_at = ? "
                        + "WHERE id = ? AND ((status = 'PENDING' AND (available_at IS NULL OR available_at <= ?)) "
                        + "OR (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until <= ?))",
                owner, timestamp(leaseUntil), timestamp(now), record.id(), timestamp(now), timestamp(now)
        ) == 1).map(record -> new OutboxRecord(
                record.id(), record.aggregateType(), record.aggregateId(), record.eventType(), record.payload(),
                OutboxStatus.PROCESSING, record.attemptCount(), record.availableAt(), owner, leaseUntil,
                record.lastError(), record.createdAt()
        )).toList();
    }

    @Override
    public boolean markOutboxPublished(String outboxId, String owner, Instant publishedAt) {
        return jdbcTemplate.update(
                "UPDATE agent_task_outbox SET status = 'PUBLISHED', published_at = ?, lease_owner = NULL, "
                        + "lease_until = NULL, updated_at = ? WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?",
                timestamp(publishedAt), timestamp(publishedAt), outboxId, owner
        ) == 1;
    }

    @Override
    @Transactional
    public boolean rescheduleOutbox(
            String outboxId,
            String owner,
            Instant availableAt,
            String errorCode,
            int maxAttempts
    ) {
        Integer attempts = jdbcTemplate.query(
                "SELECT attempt_count FROM agent_task_outbox WHERE id = ? AND status = 'PROCESSING' "
                        + "AND lease_owner = ? FOR UPDATE",
                resultSet -> resultSet.next() ? resultSet.getInt(1) : null, outboxId, owner
        );
        if (attempts == null) {
            return false;
        }
        int nextAttempts = attempts + 1;
        String status = nextAttempts >= maxAttempts ? OutboxStatus.DEAD.name() : OutboxStatus.PENDING.name();
        return jdbcTemplate.update(
                "UPDATE agent_task_outbox SET status = ?, attempt_count = ?, available_at = ?, last_error = ?, "
                        + "lease_owner = NULL, lease_until = NULL, updated_at = ? WHERE id = ? AND lease_owner = ?",
                status, nextAttempts, timestamp(availableAt), errorCode, timestamp(Instant.now()), outboxId, owner
        ) == 1;
    }

    @Override
    public ToolExecutionRecord saveToolExecution(ToolExecutionRecord execution) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_tool_execution (id, task_id, conversation_id, tool_code, tool_version_id, status, "
                        + "input_summary, output_summary, external_ref, trace_id, started_at, finished_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), output_summary = VALUES(output_summary), "
                        + "external_ref = VALUES(external_ref), finished_at = VALUES(finished_at), updated_at = VALUES(updated_at)",
                execution.id(), execution.taskId(), execution.conversationId(), execution.toolCode(), execution.toolVersionId(),
                execution.status().name(), json(execution.inputSummary()), json(execution.outputSummary()),
                execution.externalRef(), execution.traceId(), timestamp(execution.startedAt()),
                timestamp(execution.finishedAt()), timestamp(execution.startedAt()), timestamp(now)
        );
        return execution;
    }

    @Override
    public List<ToolExecutionRecord> toolExecutions(String visitorId, String taskId) {
        findTask(visitorId, taskId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
        return jdbcTemplate.query(
                "SELECT * FROM agent_tool_execution WHERE task_id = ? ORDER BY started_at",
                (resultSet, rowNum) -> toolExecution(resultSet), taskId
        );
    }

    @Override
    public void saveAudit(AuditRecord auditRecord) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_audit_event (id, visitor_id, task_id, request_id, event_type, actor_type, "
                        + "metadata_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)",
                auditRecord.id(), auditRecord.visitorId(), auditRecord.taskId(), auditRecord.requestId(),
                auditRecord.eventType(), auditRecord.actorType(), json(auditRecord.metadata()),
                timestamp(auditRecord.createdAt()), timestamp(now)
        );
    }

    @Override
    public List<AuditRecord> audits(String visitorId, String taskId) {
        findTask(visitorId, taskId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
        return jdbcTemplate.query(
                "SELECT * FROM agent_audit_event WHERE task_id = ? ORDER BY created_at",
                (resultSet, rowNum) -> audit(resultSet), taskId
        );
    }

    @Override
    public void saveGraphCheckpoint(GraphCheckpointRecord checkpoint) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_graph_checkpoint (graph_thread_id, checkpoint_id, state_json, node_id, "
                        + "next_node_id, created_at, updated_at) VALUES (?, ?, CAST(? AS JSON), ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE state_json = VALUES(state_json), node_id = VALUES(node_id), "
                        + "next_node_id = VALUES(next_node_id), updated_at = VALUES(updated_at)",
                checkpoint.graphThreadId(), checkpoint.checkpointId(), json(checkpoint.state()), checkpoint.nodeId(),
                checkpoint.nextNodeId(), timestamp(checkpoint.createdAt()), timestamp(now)
        );
    }

    @Override
    public List<GraphCheckpointRecord> graphCheckpoints(String graphThreadId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_graph_checkpoint WHERE graph_thread_id = ? ORDER BY created_at DESC",
                (resultSet, rowNum) -> new GraphCheckpointRecord(
                        resultSet.getString("graph_thread_id"), resultSet.getString("checkpoint_id"),
                        map(resultSet.getString("state_json")), resultSet.getString("node_id"),
                        resultSet.getString("next_node_id"), instant(resultSet, "created_at")
                ), graphThreadId
        );
    }

    @Override
    public void deleteGraphCheckpoints(String graphThreadId) {
        jdbcTemplate.update("DELETE FROM agent_graph_checkpoint WHERE graph_thread_id = ?", graphThreadId);
    }

    @Override
    @Transactional
    public void saveEvent(StreamEvent event) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_stream_event "
                        + "(id, conversation_id, request_id, sequence_no, event_type, agent_code, action_code, "
                        + "payload_json, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)",
                "evt_" + UUID.randomUUID(), event.conversationId(), event.requestId(), event.sequence(), event.type(),
                agentCode(event.payload()), stringValue(event.payload().get("actionCode")),
                json(event.payload()), timestamp(event.timestamp()), timestamp(now)
        );
        jdbcTemplate.update(
                "INSERT INTO agent_task_outbox "
                        + "(id, aggregate_type, aggregate_id, event_type, payload_json, status, attempt_count, created_at, updated_at) "
                        + "VALUES (?, 'CONVERSATION', ?, ?, CAST(? AS JSON), 'PENDING', 0, ?, ?)",
                "obx_" + UUID.randomUUID(), event.conversationId(), event.type(), json(event.payload()), timestamp(now), timestamp(now)
        );
    }

    @Override
    public List<StreamEvent> events(String visitorId, String conversationId, long afterSequence, int limit) {
        requireConversation(visitorId, conversationId);
        return jdbcTemplate.query(
                "SELECT event_type, conversation_id, request_id, sequence_no, created_at, payload_json "
                        + "FROM agent_stream_event WHERE conversation_id = ? AND sequence_no > ? "
                        + "ORDER BY sequence_no LIMIT ?",
                (resultSet, rowNum) -> new StreamEvent(
                        resultSet.getString("event_type"),
                        resultSet.getString("conversation_id"),
                        resultSet.getString("request_id"),
                        resultSet.getLong("sequence_no"),
                        instant(resultSet, "created_at"),
                        map(resultSet.getString("payload_json"))
                ), conversationId, afterSequence, limit
        );
    }

    @Override
    public void saveRouteDecision(RouteDecisionRecord decision) {
        Instant updatedAt = Instant.now();
        try {
            jdbcTemplate.update(
                    "INSERT INTO agent_route_decision "
                            + "(id, visitor_id, conversation_id, request_id, route_sequence, source_agent_code, "
                            + "target_agent_code, route_type, route_source, confidence, reason_code, candidate_agents_json, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)",
                    decision.id(), decision.visitorId(), decision.conversationId(), decision.requestId(),
                    decision.routeSequence(), decision.sourceAgentCode(), decision.targetAgentCode(),
                    decision.routeType().name(), decision.routeSource().name(), decision.confidence(), decision.reasonCode(),
                    json(decision.candidateAgentCodes()), timestamp(decision.createdAt()), timestamp(updatedAt)
            );
        } catch (DuplicateKeyException ignored) {
            // The request/route sequence is the durable idempotency boundary for route facts.
        }
    }

    @Override
    public Optional<RouteDecisionRecord> latestRoute(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return jdbcTemplate.query(
                "SELECT * FROM agent_route_decision WHERE conversation_id = ? "
                        + "ORDER BY created_at DESC, route_sequence DESC LIMIT 1",
                (resultSet, rowNum) -> routeDecision(resultSet),
                conversationId
        ).stream().findFirst();
    }

    @Override
    public Optional<PendingAction> findActivePending(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return jdbcTemplate.query(
                "SELECT id, conversation_id, domain_code, action_code, input_json, expires_at "
                        + "FROM agent_pending_action WHERE conversation_id = ? AND expires_at > ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                (resultSet, rowNum) -> pending(resultSet),
                conversationId,
                timestamp(Instant.now())
        ).stream().findFirst();
    }

    @Override
    public List<AgentTask> findActiveTasks(String visitorId, String conversationId) {
        requireConversation(visitorId, conversationId);
        return jdbcTemplate.query(
                "SELECT * FROM agent_task WHERE conversation_id = ? "
                        + "AND status IN ('WAITING_CONFIRMATION', 'WAITING_EXTERNAL_RESULT', 'DISPATCHED') "
                        + "ORDER BY created_at DESC",
                (resultSet, rowNum) -> task(resultSet),
                conversationId
        );
    }

    @Override
    public boolean updateConversationRoute(
            String visitorId,
            String conversationId,
            long expectedVersion,
            String targetAgentCode
    ) {
        return jdbcTemplate.update(
                "UPDATE agent_conversation SET active_agent_code = ?, routing_version = routing_version + 1, "
                        + "updated_at = ? WHERE id = ? AND visitor_id = ? AND routing_version = ?",
                targetAgentCode,
                timestamp(Instant.now()),
                conversationId,
                visitorId,
                expectedVersion
        ) == 1;
    }

    @Override
    public List<TimelineItem> timeline(
            String visitorId,
            String conversationId,
            long afterSequence,
            int limit
    ) {
        requireConversation(visitorId, conversationId);
        List<TimelineItem> timeline = new ArrayList<>();
        messages(visitorId, conversationId).stream()
                .filter(message -> message.sequence() > afterSequence)
                .map(message -> new TimelineItem(
                        message.sequence(), "MESSAGE", message.role(), message.content(), message.eventType(), null,
                        message.agentCode(), message.actionCode(), message.createdAt(), Map.of()
                ))
                .forEach(timeline::add);
        events(visitorId, conversationId, afterSequence, limit).stream()
                .filter(event -> !"message.final".equals(event.type()))
                .map(this::timelineEvent)
                .forEach(timeline::add);
        return timeline.stream()
                .sorted(Comparator.comparingLong(TimelineItem::sequence))
                .limit(limit)
                .toList();
    }

    @Override
    public RouteMetrics routeMetrics(String agentCode) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_route_decision WHERE target_agent_code = ?",
                Long.class,
                agentCode
        );
        Long ambiguous = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_route_decision WHERE route_type = 'CLARIFICATION_REQUIRED' "
                        + "AND JSON_CONTAINS(candidate_agents_json, JSON_QUOTE(?))",
                Long.class,
                agentCode
        );
        Long failure = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_route_decision WHERE route_type = 'UNSUPPORTED' "
                        + "AND JSON_CONTAINS(candidate_agents_json, JSON_QUOTE(?))",
                Long.class,
                agentCode
        );
        Long switches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_route_decision WHERE target_agent_code = ? "
                        + "AND source_agent_code <> 'group-assistant' AND source_agent_code <> target_agent_code",
                Long.class,
                agentCode
        );
        return new RouteMetrics(value(total), value(ambiguous), value(failure), value(switches));
    }

    private Conversation conversation(ResultSet resultSet) throws SQLException {
        return new Conversation(
                resultSet.getString("id"), resultSet.getString("visitor_id"), resultSet.getString("graph_thread_id"),
                resultSet.getString("title"), resultSet.getString("active_agent_code"),
                resultSet.getLong("routing_version"), instant(resultSet, "created_at"), instant(resultSet, "last_message_at")
        );
    }

    private AgentTask task(ResultSet resultSet) throws SQLException {
        return new AgentTask(
                resultSet.getString("id"), resultSet.getString("visitor_id"), resultSet.getString("conversation_id"),
                resultSet.getString("domain_code"), resultSet.getString("action_code"),
                resultSet.getString("idempotency_key"), map(resultSet.getString("input_json")),
                TaskStatus.valueOf(resultSet.getString("status")), resultSet.getInt("confirmation_version"),
                resultSet.getLong("version"), resultSet.getString("confirmation_snapshot_hash"),
                nullableInstant(resultSet, "confirmation_expires_at"), resultSet.getString("external_ref"),
                resultSet.getString("result_summary"), resultSet.getString("last_error_code"),
                nullableInstant(resultSet, "execution_lease_until"), nullableInstant(resultSet, "next_recovery_at"),
                resultSet.getInt("recovery_attempts"), instant(resultSet, "created_at"), instant(resultSet, "updated_at")
        );
    }

    private OutboxRecord outbox(ResultSet resultSet) throws SQLException {
        return new OutboxRecord(
                resultSet.getString("id"), resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"), resultSet.getString("event_type"),
                map(resultSet.getString("payload_json")), OutboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"), nullableInstant(resultSet, "available_at"),
                resultSet.getString("lease_owner"), nullableInstant(resultSet, "lease_until"),
                resultSet.getString("last_error"), instant(resultSet, "created_at")
        );
    }

    private ToolExecutionRecord toolExecution(ResultSet resultSet) throws SQLException {
        return new ToolExecutionRecord(
                resultSet.getString("id"), resultSet.getString("task_id"), resultSet.getString("conversation_id"),
                resultSet.getString("tool_code"), resultSet.getString("tool_version_id"),
                ToolExecutionStatus.valueOf(resultSet.getString("status")),
                map(resultSet.getString("input_summary")), map(resultSet.getString("output_summary")),
                resultSet.getString("external_ref"), resultSet.getString("trace_id"),
                instant(resultSet, "started_at"), nullableInstant(resultSet, "finished_at")
        );
    }

    private AuditRecord audit(ResultSet resultSet) throws SQLException {
        return new AuditRecord(
                resultSet.getString("id"), resultSet.getString("visitor_id"), resultSet.getString("task_id"),
                resultSet.getString("request_id"), resultSet.getString("event_type"),
                resultSet.getString("actor_type"), map(resultSet.getString("metadata_json")),
                instant(resultSet, "created_at")
        );
    }

    private PendingAction pending(ResultSet resultSet) throws SQLException {
        String actionCode = resultSet.getString("action_code");
        String domainCode = resultSet.getString("domain_code");
        return new PendingAction(
                resultSet.getString("id"),
                resultSet.getString("conversation_id"),
                domainCode == null ? actionCode.substring(0, actionCode.indexOf('.')) : domainCode,
                actionCode,
                map(resultSet.getString("input_json")),
                instant(resultSet, "expires_at")
        );
    }

    private RouteDecisionRecord routeDecision(ResultSet resultSet) throws SQLException {
        return new RouteDecisionRecord(
                resultSet.getString("id"),
                resultSet.getString("visitor_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("request_id"),
                resultSet.getInt("route_sequence"),
                resultSet.getString("source_agent_code"),
                resultSet.getString("target_agent_code"),
                com.manzhushaka.agent.runtime.routing.RouteType.valueOf(resultSet.getString("route_type")),
                com.manzhushaka.agent.runtime.routing.RouteSource.valueOf(resultSet.getString("route_source")),
                resultSet.getObject("confidence") == null ? null : resultSet.getDouble("confidence"),
                resultSet.getString("reason_code"),
                stringList(resultSet.getString("candidate_agents_json")),
                instant(resultSet, "created_at")
        );
    }

    private TimelineItem timelineEvent(StreamEvent event) {
        return new TimelineItem(
                event.sequence(), "EVENT", "SYSTEM", "", event.type(), event.requestId(),
                agentCode(event.payload()), stringValue(event.payload().get("actionCode")), event.timestamp(), event.payload()
        );
    }

    private Map<String, Object> map(String value) {
        try {
            return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Runtime JSON is corrupted", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Runtime state", exception);
        }
    }

    private List<String> stringList(String value) {
        try {
            return value == null || value.isBlank() ? List.of() : objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Runtime JSON is corrupted", exception);
        }
    }

    private String agentCode(Map<String, Object> payload) {
        if (payload.get("agent") instanceof Map<?, ?> agent) {
            return stringValue(agent.get("code"));
        }
        return stringValue(payload.get("targetAgentCode"));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private void requireConversation(String visitorId, String conversationId) {
        if (findConversation(visitorId, conversationId).isEmpty()) {
            throw new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "会话不存在或不属于当前访客");
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private void enqueueTaskState(String taskId, TaskStatus status, long version, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO agent_task_outbox "
                        + "(id, aggregate_type, aggregate_id, event_type, payload_json, status, attempt_count, "
                        + "available_at, created_at, updated_at) "
                        + "VALUES (?, 'TASK', ?, 'task.status', CAST(? AS JSON), 'PENDING', 0, ?, ?, ?)",
                "obx_" + UUID.randomUUID(), taskId,
                json(Map.of("taskId", taskId, "status", status.name(), "version", version)),
                timestamp(now), timestamp(now), timestamp(now)
        );
    }
}
