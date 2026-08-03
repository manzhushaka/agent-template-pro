package com.manzhushaka.agent.infrastructure.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.runtime.store.SpanPage;
import com.manzhushaka.agent.runtime.store.SpanQuery;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.store.TraceStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** MySQL-backed span store; only whitelisted span fields and masked metadata are written. */
@Repository
@Profile("runtime-jdbc")
public class JdbcTraceStore implements TraceStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcTraceStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveSpan(SpanRecord span) {
        jdbcTemplate.update(
                "INSERT INTO agent_runtime_span "
                        + "(id, trace_id, parent_span_id, span_type, span_name, status, visitor_ref, "
                        + "conversation_id, task_id, request_id, agent_code, action_code, tool_code, "
                        + "model_provider, model_name, input_tokens, output_tokens, total_tokens, "
                        + "duration_ms, error_code, resource_versions_json, metadata_json, "
                        + "started_at, finished_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                span.id(), span.traceId(), span.parentSpanId(), span.type().name(), span.name(),
                span.status().name(), span.visitorRef(), span.conversationId(), span.taskId(),
                span.requestId(), span.agentCode(), span.actionCode(), span.toolCode(),
                span.modelProvider(), span.modelName(), span.inputTokens(), span.outputTokens(),
                span.totalTokens(), span.durationMs(), span.errorCode(),
                json(span.resourceVersions()), json(span.metadata()),
                timestamp(span.startedAt()), timestamp(span.finishedAt()), timestamp(span.createdAt())
        );
    }

    @Override
    public SpanPage querySpans(SpanQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, query);
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runtime_span " + where, Long.class, args.toArray()
        );
        if (total == 0) {
            return new SpanPage(List.of(), 0);
        }
        List<SpanRecord> items = jdbcTemplate.query(
                "SELECT * FROM agent_runtime_span " + where
                        + " ORDER BY started_at DESC LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> span(resultSet),
                appendPaging(args, query)
        );
        return new SpanPage(items, total);
    }

    @Override
    public List<SpanRecord> spansByTrace(String traceId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_runtime_span WHERE trace_id = ? ORDER BY started_at ASC",
                (resultSet, rowNum) -> span(resultSet), traceId
        );
    }

    @Override
    public Optional<SpanRecord> findSpan(String spanId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_runtime_span WHERE id = ?",
                (resultSet, rowNum) -> span(resultSet), spanId
        ).stream().findFirst();
    }

    @Override
    public List<SpanRecord> recentSpans(SpanType type, int limit) {
        if (type == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM agent_runtime_span ORDER BY started_at DESC LIMIT ?",
                    (resultSet, rowNum) -> span(resultSet), Math.max(1, limit)
            );
        }
        return jdbcTemplate.query(
                "SELECT * FROM agent_runtime_span WHERE span_type = ? ORDER BY started_at DESC LIMIT ?",
                (resultSet, rowNum) -> span(resultSet), type.name(), Math.max(1, limit)
        );
    }

    private static void appendFilters(StringBuilder where, List<Object> args, SpanQuery query) {
        if (query.type() != null) {
            where.append(" AND span_type = ? ");
            args.add(query.type().name());
        }
        if (query.status() != null) {
            where.append(" AND status = ? ");
            args.add(query.status().name());
        }
        if (query.conversationId() != null && !query.conversationId().isBlank()) {
            where.append(" AND conversation_id = ? ");
            args.add(query.conversationId());
        }
        if (query.taskId() != null && !query.taskId().isBlank()) {
            where.append(" AND task_id = ? ");
            args.add(query.taskId());
        }
        if (query.requestId() != null && !query.requestId().isBlank()) {
            where.append(" AND request_id = ? ");
            args.add(query.requestId());
        }
        if (query.agentCode() != null && !query.agentCode().isBlank()) {
            where.append(" AND agent_code = ? ");
            args.add(query.agentCode());
        }
        if (query.from() != null) {
            where.append(" AND started_at >= ? ");
            args.add(timestamp(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND started_at <= ? ");
            args.add(timestamp(query.to()));
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String keyword = "%" + query.keyword().trim().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(id) LIKE ? OR LOWER(trace_id) LIKE ? OR LOWER(span_name) LIKE ? "
                    + "OR LOWER(conversation_id) LIKE ? OR LOWER(task_id) LIKE ? OR LOWER(request_id) LIKE ? "
                    + "OR LOWER(agent_code) LIKE ? OR LOWER(action_code) LIKE ? OR LOWER(tool_code) LIKE ? "
                    + "OR LOWER(model_name) LIKE ? OR LOWER(error_code) LIKE ?) ");
            for (int i = 0; i < 11; i++) {
                args.add(keyword);
            }
        }
    }

    private static Object[] appendPaging(List<Object> args, SpanQuery query) {
        args.add(query.size());
        args.add((long) (query.page() - 1) * query.size());
        return args.toArray();
    }

    private SpanRecord span(ResultSet resultSet) throws SQLException {
        return new SpanRecord(
                resultSet.getString("id"),
                resultSet.getString("trace_id"),
                resultSet.getString("parent_span_id"),
                SpanType.valueOf(resultSet.getString("span_type")),
                resultSet.getString("span_name"),
                SpanStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("visitor_ref"),
                resultSet.getString("conversation_id"),
                resultSet.getString("task_id"),
                resultSet.getString("request_id"),
                resultSet.getString("agent_code"),
                resultSet.getString("action_code"),
                resultSet.getString("tool_code"),
                resultSet.getString("model_provider"),
                resultSet.getString("model_name"),
                resultSet.getInt("input_tokens"),
                resultSet.getInt("output_tokens"),
                resultSet.getInt("total_tokens"),
                resultSet.getLong("duration_ms"),
                resultSet.getString("error_code"),
                map(resultSet.getString("resource_versions_json")),
                map(resultSet.getString("metadata_json")),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("finished_at")),
                instant(resultSet.getTimestamp("created_at"))
        );
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 span 元数据", exception);
        }
    }

    private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析 span 元数据", exception);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.now() : instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
