package com.manzhushaka.agent.controlplane.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL-backed workflow repository. */
@Repository
@Profile("runtime-jdbc")
public class JdbcWorkflowRepository implements WorkflowRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<WorkflowDefinition> workflow(String id) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow WHERE id=?",
                (resultSet, rowNum) -> workflow(resultSet), id
        ).stream().findFirst();
    }

    @Override
    public Optional<WorkflowDefinition> workflowByCode(String code) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow WHERE code=?",
                (resultSet, rowNum) -> workflow(resultSet), code
        ).stream().findFirst();
    }

    @Override
    public WorkflowDefinition saveWorkflow(WorkflowDefinition workflow) {
        int updated = jdbcTemplate.update(
                "UPDATE agent_workflow SET display_name=?, description_text=?, status=?, current_version_id=?, "
                        + "updated_at=? WHERE id=?",
                workflow.displayName(), workflow.description(), workflow.status(),
                workflow.currentVersionId(), Timestamp.from(workflow.updatedAt()), workflow.id()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_workflow (id, code, display_name, description_text, status, "
                            + "current_version_id, created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    workflow.id(), workflow.code(), workflow.displayName(), workflow.description(),
                    workflow.status(), workflow.currentVersionId(), workflow.createdBy(),
                    Timestamp.from(workflow.createdAt()), Timestamp.from(workflow.updatedAt())
            );
        }
        return workflow;
    }

    @Override
    public List<WorkflowDefinition> workflows(String keyword, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code LIKE ? OR display_name LIKE ?) ");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        args.add(size);
        args.add((long) (page - 1) * size);
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> workflow(resultSet), args.toArray()
        );
    }

    @Override
    public long countWorkflows(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_workflow", Long.class);
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_workflow WHERE code LIKE ? OR display_name LIKE ?",
                Long.class, "%" + keyword + "%", "%" + keyword + "%"
        );
    }

    @Override
    public Optional<WorkflowVersion> version(String versionId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_version WHERE id=?",
                (resultSet, rowNum) -> version(resultSet), versionId
        ).stream().findFirst();
    }

    @Override
    public List<WorkflowVersion> versions(String workflowId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_workflow_version WHERE workflow_id=? ORDER BY version_no ASC",
                (resultSet, rowNum) -> version(resultSet), workflowId
        );
    }

    @Override
    public WorkflowVersion saveVersion(WorkflowVersion version) {
        int updated = jdbcTemplate.update(
                "UPDATE agent_workflow_version SET status=?, resource_bindings_json=?, description_text=?, "
                        + "published_at=?, updated_at=? WHERE id=?",
                version.status(), json(version.resourceBindings()), version.description(),
                timestamp(version.publishedAt()), Timestamp.from(version.updatedAt()), version.id()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_workflow_version (id, workflow_id, version_no, status, schema_version, "
                            + "dsl_json, resource_bindings_json, description_text, created_by, published_at, "
                            + "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    version.id(), version.workflowId(), version.versionNo(), version.status(),
                    version.schemaVersion(), version.dslJson(), json(version.resourceBindings()),
                    version.description(), version.createdBy(), timestamp(version.publishedAt()),
                    Timestamp.from(version.createdAt()), Timestamp.from(version.updatedAt())
            );
        }
        return version;
    }

    @Override
    public int nextVersionNo(String workflowId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM agent_workflow_version WHERE workflow_id=?",
                Integer.class, workflowId
        );
        return max + 1;
    }

    private WorkflowDefinition workflow(ResultSet resultSet) throws SQLException {
        return new WorkflowDefinition(
                resultSet.getString("id"),
                resultSet.getString("code"),
                resultSet.getString("display_name"),
                resultSet.getString("description_text"),
                resultSet.getString("status"),
                resultSet.getString("current_version_id"),
                resultSet.getString("created_by"),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private WorkflowVersion version(ResultSet resultSet) throws SQLException {
        return new WorkflowVersion(
                resultSet.getString("id"),
                resultSet.getString("workflow_id"),
                resultSet.getInt("version_no"),
                resultSet.getString("status"),
                resultSet.getString("schema_version"),
                resultSet.getString("dsl_json"),
                map(resultSet.getString("resource_bindings_json")),
                resultSet.getString("description_text"),
                resultSet.getString("created_by"),
                instant(resultSet.getTimestamp("published_at")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析工作流绑定 JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化工作流绑定 JSON", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
