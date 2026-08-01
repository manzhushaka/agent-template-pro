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
import com.manzhushaka.agent.runtime.store.MessageAppend;
import com.manzhushaka.agent.runtime.store.RouteDecisionRecord;
import com.manzhushaka.agent.runtime.store.RouteMetrics;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.store.TimelineItem;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
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
                            + "input_json, confirmation_version, confirmation_snapshot_hash, version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?)",
                    task.id(), task.visitorId(), task.conversationId(), task.domainCode(), task.actionCode(),
                    task.status().name(), task.externalRef(), task.idempotencyKey(), json(task.input()),
                    task.confirmationVersion(), task.confirmationSnapshotHash(), task.version(), timestamp(task.createdAt()), timestamp(now)
            );
            if (task.status() == TaskStatus.WAITING_CONFIRMATION) {
                jdbcTemplate.update(
                        "INSERT INTO agent_confirmation "
                                + "(id, task_id, confirmation_version, snapshot_hash, decision, confirmed_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NULL, NULL, ?, ?)",
                        "cfm_" + UUID.randomUUID(), task.id(), task.confirmationVersion(), task.confirmationSnapshotHash(),
                        timestamp(now), timestamp(now)
                );
            }
            enqueueTaskState(task.id(), task.status(), now);
            return task;
        } catch (DuplicateKeyException duplicateKeyException) {
            return jdbcTemplate.query(
                    "SELECT * FROM agent_task WHERE action_code = ? AND idempotency_key = ?",
                    (resultSet, rowNum) -> task(resultSet), task.actionCode(), task.idempotencyKey()
            ).stream().findFirst().orElseThrow(() -> duplicateKeyException);
        }
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
            int expectedConfirmationVersion,
            TaskStatus targetStatus,
            String externalRef
    ) {
        if (!expectedStatus.canTransitionTo(targetStatus)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                "UPDATE agent_task SET status = ?, external_ref = COALESCE(?, external_ref), version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND visitor_id = ? AND status = ? AND confirmation_version = ?",
                targetStatus.name(), externalRef, timestamp(now), taskId, visitorId, expectedStatus.name(), expectedConfirmationVersion
        );
        if (updated == 0) {
            return Optional.empty();
        }
        if (targetStatus == TaskStatus.CANCELLED || targetStatus == TaskStatus.DISPATCHED) {
            jdbcTemplate.update(
                    "UPDATE agent_confirmation SET decision = ?, confirmed_at = ?, updated_at = ? "
                            + "WHERE task_id = ? AND confirmation_version = ? AND decision IS NULL",
                    targetStatus == TaskStatus.CANCELLED ? "REJECTED" : "CONFIRMED", timestamp(now), timestamp(now),
                    taskId, expectedConfirmationVersion
            );
        }
        enqueueTaskState(taskId, targetStatus, now);
        return findTask(visitorId, taskId);
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

    @Override
    public void audit(String visitorId, String taskId, String type) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_audit_event "
                        + "(id, visitor_id, task_id, event_type, actor_type, metadata_json, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'SYSTEM', NULL, ?, ?)",
                "aud_" + UUID.randomUUID(), visitorId, taskId, type, timestamp(now), timestamp(now)
        );
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
                resultSet.getString("external_ref"), instant(resultSet, "created_at"), instant(resultSet, "updated_at")
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

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private void enqueueTaskState(String taskId, TaskStatus status, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO agent_task_outbox "
                        + "(id, aggregate_type, aggregate_id, event_type, payload_json, status, attempt_count, created_at, updated_at) "
                        + "VALUES (?, 'TASK', ?, 'task.status', CAST(? AS JSON), 'PENDING', 0, ?, ?)",
                "obx_" + UUID.randomUUID(), taskId, json(Map.of("taskId", taskId, "status", status.name())),
                timestamp(now), timestamp(now)
        );
    }
}
