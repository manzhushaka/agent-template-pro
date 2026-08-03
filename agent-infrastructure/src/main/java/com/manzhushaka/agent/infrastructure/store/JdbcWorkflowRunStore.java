package com.manzhushaka.agent.infrastructure.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.runtime.workflow.WorkflowEvent;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRunStatus;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import com.manzhushaka.agent.runtime.workflow.WorkflowRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunPage;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStatus;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** MySQL-backed workflow run store. Every node checkpoint is a full row rewrite within one statement. */
@Repository
@Profile("runtime-jdbc")
public class JdbcWorkflowRunStore implements WorkflowRunStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowRunStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkflowRun saveRun(WorkflowRun run) {
        int updated = jdbcTemplate.update(
                "UPDATE agent_workflow_run SET workflow_version_id=?, code=?, dsl_json=?, graph_thread_id=?, "
                        + "status=?, visitor_ref=?, request_id=?, variables_json=?, visited_node_ids_json=?, "
                        + "visited_edge_keys_json=?, pending_edge_keys_json=?, current_node_id=?, error_code=?, "
                        + "claim_owner=?, claim_lease_until=?, finished_at=?, updated_at=? WHERE id=?",
                run.workflowVersionId(), run.code(), run.dslJson(), run.graphThreadId(),
                run.status().name(), run.visitorRef(), run.requestId(),
                json(run.variables()), json(run.visitedNodeIds()), json(run.visitedEdgeKeys()),
                json(run.pendingEdgeKeys()), run.currentNodeId(), run.errorCode(),
                run.claimOwner(), timestamp(run.claimLeaseUntil()), timestamp(run.finishedAt()),
                timestamp(run.updatedAt()), run.id()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_workflow_run (id, workflow_id, workflow_version_id, code, dsl_json, "
                            + "graph_thread_id, status, visitor_ref, request_id, variables_json, "
                            + "visited_node_ids_json, visited_edge_keys_json, pending_edge_keys_json, "
                            + "current_node_id, error_code, claim_owner, claim_lease_until, started_at, "
                            + "finished_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.id(), run.workflowId(), run.workflowVersionId(), run.code(), run.dslJson(),
                    run.graphThreadId(), run.status().name(), run.visitorRef(), run.requestId(),
                    json(run.variables()), json(run.visitedNodeIds()), json(run.visitedEdgeKeys()),
                    json(run.pendingEdgeKeys()), run.currentNodeId(), run.errorCode(),
                    run.claimOwner(), timestamp(run.claimLeaseUntil()), timestamp(run.startedAt()),
                    timestamp(run.finishedAt()), timestamp(run.createdAt()), timestamp(run.updatedAt())
            );
        }
        return run;
    }

    @Override
    public boolean transitionRunStatus(String runId, WorkflowRunStatus expected, WorkflowRunStatus target) {
        return jdbcTemplate.update(
                "UPDATE agent_workflow_run SET status=?, updated_at=? WHERE id=? AND status=?",
                target.name(), Timestamp.from(Instant.now()), runId, expected.name()
        ) == 1;
    }

    @Override
    public Optional<WorkflowRun> findRun(String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_run WHERE id=?",
                (resultSet, rowNum) -> run(resultSet), runId
        ).stream().findFirst();
    }

    @Override
    public Optional<WorkflowRun> findRunForVisitor(String visitorRef, String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_run WHERE id=? AND visitor_ref=?",
                (resultSet, rowNum) -> run(resultSet), runId, visitorRef
        ).stream().findFirst();
    }

    @Override
    public WorkflowRunPage listRuns(String keyword, String status, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ? ");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code LIKE ? OR id LIKE ?) ");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_workflow_run " + where, Long.class, args.toArray()
        );
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        List<WorkflowRun> items = jdbcTemplate.query(
                "SELECT * FROM agent_workflow_run " + where
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> run(resultSet), pageArgs.toArray()
        );
        return new WorkflowRunPage(items, total);
    }

    @Override
    public List<WorkflowRun> findStaleRunning(Instant now, Duration lease) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_run WHERE status='RUNNING' AND updated_at < ?",
                (resultSet, rowNum) -> run(resultSet),
                Timestamp.from(now.minus(lease))
        );
    }

    @Override
    public WorkflowNodeRun saveNodeRun(WorkflowNodeRun nodeRun) {
        int updated = jdbcTemplate.update(
                "UPDATE agent_workflow_node_run SET status=?, input_json=?, output_json=?, "
                        + "confirmation_task_id=?, confirmation_version=?, confirmation_snapshot_hash=?, "
                        + "retry_count=?, error_code=?, finished_at=?, updated_at=? WHERE id=?",
                nodeRun.status().name(), json(nodeRun.input()), json(nodeRun.output()),
                nodeRun.confirmationTaskId(), nodeRun.confirmationVersion(),
                nodeRun.confirmationSnapshotHash(), nodeRun.retryCount(), nodeRun.errorCode(),
                timestamp(nodeRun.finishedAt()), timestamp(nodeRun.updatedAt()), nodeRun.id()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_workflow_node_run (id, run_id, node_id, node_type, status, input_json, "
                            + "output_json, confirmation_task_id, confirmation_version, confirmation_snapshot_hash, "
                            + "retry_count, error_code, started_at, finished_at, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    nodeRun.id(), nodeRun.runId(), nodeRun.nodeId(), nodeRun.nodeType().name(),
                    nodeRun.status().name(), json(nodeRun.input()), json(nodeRun.output()),
                    nodeRun.confirmationTaskId(), nodeRun.confirmationVersion(),
                    nodeRun.confirmationSnapshotHash(), nodeRun.retryCount(), nodeRun.errorCode(),
                    timestamp(nodeRun.startedAt()), timestamp(nodeRun.finishedAt()),
                    timestamp(nodeRun.createdAt()), timestamp(nodeRun.updatedAt())
            );
        }
        return nodeRun;
    }

    @Override
    public Optional<WorkflowNodeRun> findNodeRun(String runId, String nodeId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_node_run WHERE run_id=? AND node_id=?",
                (resultSet, rowNum) -> nodeRun(resultSet), runId, nodeId
        ).stream().findFirst();
    }

    @Override
    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_node_run WHERE run_id=? ORDER BY started_at ASC",
                (resultSet, rowNum) -> nodeRun(resultSet), runId
        );
    }

    @Override
    public void saveEvent(WorkflowEvent event) {
        jdbcTemplate.update(
                "INSERT INTO agent_workflow_event (id, run_id, sequence, event_type, node_id, payload_json, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                event.id(), event.runId(), event.sequence(), event.type(), event.nodeId(),
                json(event.payload()), timestamp(event.createdAt())
        );
    }

    @Override
    public List<WorkflowEvent> events(String runId, long afterSequence, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_event WHERE run_id=? AND sequence > ? ORDER BY sequence ASC LIMIT ?",
                (resultSet, rowNum) -> event(resultSet), runId, afterSequence, limit
        );
    }

    private WorkflowRun run(ResultSet resultSet) throws SQLException {
        return new WorkflowRun(
                resultSet.getString("id"),
                resultSet.getString("workflow_id"),
                resultSet.getString("workflow_version_id"),
                resultSet.getString("code"),
                resultSet.getString("dsl_json"),
                resultSet.getString("graph_thread_id"),
                WorkflowRunStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("visitor_ref"),
                resultSet.getString("request_id"),
                map(resultSet.getString("variables_json")),
                stringList(resultSet.getString("visited_node_ids_json")),
                stringList(resultSet.getString("visited_edge_keys_json")),
                stringList(resultSet.getString("pending_edge_keys_json")),
                resultSet.getString("current_node_id"),
                resultSet.getString("error_code"),
                resultSet.getString("claim_owner"),
                instant(resultSet.getTimestamp("claim_lease_until")),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("finished_at")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private WorkflowNodeRun nodeRun(ResultSet resultSet) throws SQLException {
        return new WorkflowNodeRun(
                resultSet.getString("id"),
                resultSet.getString("run_id"),
                resultSet.getString("node_id"),
                WorkflowNodeType.valueOf(resultSet.getString("node_type")),
                WorkflowNodeRunStatus.valueOf(resultSet.getString("status")),
                map(resultSet.getString("input_json")),
                map(resultSet.getString("output_json")),
                resultSet.getString("confirmation_task_id"),
                resultSet.getInt("confirmation_version"),
                resultSet.getString("confirmation_snapshot_hash"),
                resultSet.getInt("retry_count"),
                resultSet.getString("error_code"),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("finished_at")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private WorkflowEvent event(ResultSet resultSet) throws SQLException {
        return new WorkflowEvent(
                resultSet.getString("id"),
                resultSet.getString("run_id"),
                resultSet.getLong("sequence"),
                resultSet.getString("event_type"),
                resultSet.getString("node_id"),
                map(resultSet.getString("payload_json")),
                instant(resultSet.getTimestamp("created_at"))
        );
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析工作流 JSON 字段", exception);
        }
    }

    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析工作流字符串列表字段", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化工作流 JSON 字段", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
