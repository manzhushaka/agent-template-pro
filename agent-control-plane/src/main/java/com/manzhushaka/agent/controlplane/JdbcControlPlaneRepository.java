package com.manzhushaka.agent.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JdbcControlPlaneRepository implements ControlPlaneRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public JdbcControlPlaneRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<Map<String, Object>> listDocuments(String type) {
        return jdbcTemplate.query("SELECT document_json FROM agent_control_plane_document WHERE document_type = ?", (rs, row) ->
                readDocument(rs.getString(1)), type);
    }

    @Override
    public void saveDocument(String type, Map<String, Object> document) {
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO agent_control_plane_document (document_type, id, document_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE document_json = VALUES(document_json), updated_at = VALUES(updated_at)",
                type, document.get("id"), writeDocument(document), Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public void saveDocumentWithAudit(String type, Map<String, Object> document, ControlPlaneAudit audit) {
        transactionTemplate.executeWithoutResult(status -> {
            saveDocument(type, document);
            appendAudit(audit);
        });
    }

    @Override
    public Map<String, Object> createPromptVersion(Map<String, Object> version, ControlPlaneAudit audit) {
        return transactionTemplate.execute(status -> {
            String promptId = String.valueOf(version.get("promptId"));
            String lockedPromptId = jdbcTemplate.queryForObject(
                    "SELECT id FROM agent_control_plane_document "
                            + "WHERE document_type = 'PROMPT' AND id = ? FOR UPDATE",
                    String.class,
                    promptId
            );
            if (lockedPromptId == null) {
                throw new IllegalArgumentException("资源不存在。");
            }
            Integer currentVersion = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(document_json, '$.version')) AS UNSIGNED)), 0) "
                            + "FROM agent_control_plane_document WHERE document_type = 'PROMPT_VERSION' "
                            + "AND JSON_UNQUOTE(JSON_EXTRACT(document_json, '$.promptId')) = ?",
                    Integer.class,
                    promptId
            );
            Map<String, Object> persisted = new java.util.LinkedHashMap<>(version);
            persisted.put("version", (currentVersion == null ? 0 : currentVersion) + 1);
            saveDocument("PROMPT_VERSION", persisted);
            appendAudit(audit);
            return Map.copyOf(persisted);
        });
    }

    @Override
    public Map<String, Object> publishPrompt(String promptId, String targetVersionId, ControlPlaneAudit audit) {
        return transactionTemplate.execute(status -> {
            String json = jdbcTemplate.queryForObject(
                    "SELECT document_json FROM agent_control_plane_document WHERE document_type = 'PROMPT' AND id = ? FOR UPDATE",
                    String.class, promptId
            );
            if (json == null) {
                throw new IllegalArgumentException("资源不存在。");
            }
            Map<String, Object> prompt = new java.util.LinkedHashMap<>(readDocument(json));
            Object previous = prompt.put("publishedVersionId", targetVersionId);
            prompt.put("updatedAt", Instant.now().toString());
            Map<String, Object> metadata = new java.util.LinkedHashMap<>(audit.metadata());
            if (previous != null && !String.valueOf(previous).isBlank()) {
                metadata.put("previousVersionId", previous);
            }
            saveDocument("PROMPT", prompt);
            appendAudit(new ControlPlaneAudit(audit.id(), audit.actor(), audit.action(), audit.resourceType(), audit.resourceId(), metadata, audit.createdAt()));
            return Map.copyOf(prompt);
        });
    }

    @Override
    public void appendAudit(ControlPlaneAudit audit) {
        jdbcTemplate.update("INSERT INTO agent_control_plane_audit (id, actor_username, action_code, resource_type, resource_id, metadata_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                audit.id(), audit.actor(), audit.action(), audit.resourceType(), audit.resourceId(), writeDocument(audit.metadata()), Timestamp.from(audit.createdAt()));
    }

    @Override
    public List<ControlPlaneAudit> listAudits() {
        return jdbcTemplate.query("SELECT id, actor_username, action_code, resource_type, resource_id, metadata_json, created_at FROM agent_control_plane_audit ORDER BY created_at DESC", (rs, row) ->
                new ControlPlaneAudit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), readDocument(rs.getString(6)), rs.getTimestamp(7).toInstant()));
    }

    @Override
    public Set<String> permissionsForRole(String role) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT permission_code FROM agent_admin_role_permission WHERE role_code = ?",
                String.class,
                role
        ));
    }

    @Override
    public List<Map<String, Object>> listMcpResources(String type) {
        return switch (type) {
            case "MCP_SERVER" -> jdbcTemplate.query("SELECT * FROM agent_mcp_server ORDER BY updated_at DESC", (rs, row) -> mapOf(
                    "id", rs.getString("id"), "code", rs.getString("code"), "displayName", rs.getString("display_name"),
                    "transport", rs.getString("transport"), "endpoint", rs.getString("endpoint"), "command", rs.getString("command_ref"),
                    "arguments", readList(rs.getString("stdio_arguments_json")), "secretRefId", rs.getString("secret_ref_id"),
                    "enabled", rs.getBoolean("enabled"), "healthStatus", rs.getString("health_status"), "timeoutMs", rs.getLong("timeout_ms"),
                    "lastTestedAt", instant(rs.getTimestamp("last_tested_at")), "lastSyncedAt", instant(rs.getTimestamp("last_synced_at")),
                    "updatedAt", instant(rs.getTimestamp("updated_at"))));
            case "MCP_TOOL" -> jdbcTemplate.query("SELECT * FROM agent_mcp_tool ORDER BY updated_at DESC", (rs, row) -> mapOf(
                    "id", rs.getString("id"), "serverId", rs.getString("mcp_server_id"), "name", rs.getString("tool_name"),
                    "displayName", rs.getString("display_name"), "riskLevel", rs.getString("risk_level"), "writeTool", rs.getBoolean("write_tool"),
                    "enabled", rs.getBoolean("enabled"), "latestVersionId", rs.getString("latest_version_id"), "retiredAt", instant(rs.getTimestamp("retired_at")),
                    "updatedAt", instant(rs.getTimestamp("updated_at"))));
            case "MCP_TOOL_VERSION" -> jdbcTemplate.query("SELECT * FROM agent_mcp_tool_version ORDER BY created_at DESC", (rs, row) -> mapOf(
                    "id", rs.getString("id"), "toolId", rs.getString("mcp_tool_id"), "serverId", rs.getString("mcp_server_id"),
                    "toolName", rs.getString("tool_name"), "schemaDigest", rs.getString("schema_digest"), "inputSchema", readDocument(rs.getString("input_schema_json")),
                    "outputSchema", readDocument(rs.getString("output_schema_json")), "description", rs.getString("description_text"),
                    "riskLevel", rs.getString("risk_level"), "writeTool", rs.getBoolean("write_tool"), "createdAt", instant(rs.getTimestamp("created_at"))));
            case "AGENT_TOOL_BINDING" -> jdbcTemplate.query("SELECT * FROM agent_mcp_agent_binding ORDER BY updated_at DESC", (rs, row) -> mapOf(
                    "id", rs.getString("id"), "agentCode", rs.getString("agent_code"), "toolId", rs.getString("mcp_tool_id"),
                    "toolVersionId", rs.getString("mcp_tool_version_id"), "enabled", rs.getBoolean("enabled"), "updatedAt", instant(rs.getTimestamp("updated_at"))));
            default -> listDocuments(type);
        };
    }

    @Override
    public void saveMcpResourceWithAudit(String type, Map<String, Object> resource, ControlPlaneAudit audit) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                saveMcpResource(type, resource);
                appendAudit(audit);
            });
        } catch (DuplicateKeyException exception) {
            throw new McpDuplicateResourceException();
        }
    }

    @Override
    public boolean claimMcpSync(String syncId, String serverId, Instant startedAt, Instant leaseUntil) {
        Instant legacyLeaseCutoff = startedAt.minus(java.time.Duration.between(startedAt, leaseUntil));
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                    "UPDATE agent_mcp_sync_run SET active_sync_key = NULL, lease_until = NULL, status = 'FAILED', "
                            + "error_code = 'MCP_SYNC_LEASE_EXPIRED', finished_at = ? "
                            + "WHERE active_sync_key = ? AND (lease_until <= ? OR (lease_until IS NULL AND started_at <= ?))",
                    Timestamp.from(startedAt), serverId, Timestamp.from(startedAt), Timestamp.from(legacyLeaseCutoff)
            );
            try {
                jdbcTemplate.update(
                        "INSERT INTO agent_mcp_sync_run "
                                + "(id, mcp_server_id, active_sync_key, lease_until, status, discovered_tool_count, "
                                + "created_version_count, started_at) VALUES (?, ?, ?, ?, 'RUNNING', 0, 0, ?)",
                        syncId, serverId, serverId, Timestamp.from(leaseUntil), Timestamp.from(startedAt)
                );
                return true;
            } catch (DuplicateKeyException exception) {
                return false;
            }
        }));
    }

    @Override
    public void finishMcpSync(String syncId, String status, int toolCount, int versionCount, String errorCode, Instant finishedAt) {
        jdbcTemplate.update("UPDATE agent_mcp_sync_run SET active_sync_key = NULL, lease_until = NULL, status = ?, discovered_tool_count = ?, created_version_count = ?, error_code = ?, finished_at = ? WHERE id = ? AND active_sync_key IS NOT NULL",
                status, toolCount, versionCount, errorCode, Timestamp.from(finishedAt), syncId);
    }

    private void saveMcpResource(String type, Map<String, Object> value) {
        Instant now = Instant.now();
        switch (type) {
            case "MCP_SERVER" -> saveMcpServer(value, now);
            case "MCP_TOOL" -> saveMcpTool(value, now);
            case "MCP_TOOL_VERSION" -> jdbcTemplate.update("INSERT INTO agent_mcp_tool_version (id, mcp_tool_id, mcp_server_id, tool_name, schema_digest, input_schema_json, output_schema_json, description_text, risk_level, write_tool, discovered_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    value.get("id"), value.get("toolId"), value.get("serverId"), value.get("toolName"), value.get("schemaDigest"), writeDocument(map(value.get("inputSchema"))), writeDocument(map(value.get("outputSchema"))), nullable(value.get("description")), value.get("riskLevel"), bool(value.get("writeTool")), Timestamp.from(now), Timestamp.from(now));
            case "AGENT_TOOL_BINDING" -> jdbcTemplate.update("INSERT INTO agent_mcp_agent_binding (id, agent_code, mcp_tool_id, mcp_tool_version_id, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    value.get("id"), value.get("agentCode"), value.get("toolId"), value.get("toolVersionId"), bool(value.get("enabled")), Timestamp.from(now), Timestamp.from(now));
            default -> saveDocument(type, value);
        }
    }

    private void saveMcpServer(Map<String, Object> value, Instant now) {
        Object argumentsJson = writeDocument(Map.of("items", value.getOrDefault("arguments", List.of())));
        int updated = jdbcTemplate.update(
                "UPDATE agent_mcp_server SET code = ?, display_name = ?, transport = ?, endpoint = ?, command_ref = ?, "
                        + "stdio_arguments_json = ?, secret_ref_id = ?, timeout_ms = ?, enabled = ?, health_status = ?, "
                        + "last_tested_at = ?, last_synced_at = ?, updated_at = ? WHERE id = ?",
                value.get("code"), value.get("displayName"), value.get("transport"), nullable(value.get("endpoint")),
                nullable(value.get("command")), argumentsJson, nullable(value.get("secretRefId")),
                number(value.get("timeoutMs"), 5000), bool(value.get("enabled")), nullable(value.get("healthStatus")),
                timestamp(value.get("lastTestedAt")), timestamp(value.get("lastSyncedAt")), Timestamp.from(now),
                value.get("id")
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_mcp_server (id, code, display_name, transport, endpoint, command_ref, "
                            + "stdio_arguments_json, secret_ref_id, timeout_ms, enabled, health_status, last_tested_at, "
                            + "last_synced_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    value.get("id"), value.get("code"), value.get("displayName"), value.get("transport"),
                    nullable(value.get("endpoint")), nullable(value.get("command")), argumentsJson,
                    nullable(value.get("secretRefId")), number(value.get("timeoutMs"), 5000), bool(value.get("enabled")),
                    nullable(value.get("healthStatus")), timestamp(value.get("lastTestedAt")),
                    timestamp(value.get("lastSyncedAt")), Timestamp.from(now), Timestamp.from(now)
            );
        }
    }

    private void saveMcpTool(Map<String, Object> value, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE agent_mcp_tool SET mcp_server_id = ?, tool_name = ?, display_name = ?, risk_level = ?, "
                        + "write_tool = ?, enabled = ?, latest_version_id = ?, retired_at = ?, updated_at = ? WHERE id = ?",
                value.get("serverId"), value.get("name"), value.get("displayName"), value.get("riskLevel"),
                bool(value.get("writeTool")), bool(value.get("enabled")), nullable(value.get("latestVersionId")),
                timestamp(value.get("retiredAt")), Timestamp.from(now), value.get("id")
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_mcp_tool (id, mcp_server_id, tool_name, display_name, risk_level, write_tool, "
                            + "enabled, latest_version_id, retired_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    value.get("id"), value.get("serverId"), value.get("name"), value.get("displayName"),
                    value.get("riskLevel"), bool(value.get("writeTool")), bool(value.get("enabled")),
                    nullable(value.get("latestVersionId")), timestamp(value.get("retiredAt")), Timestamp.from(now),
                    Timestamp.from(now)
            );
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) if (values[index + 1] != null) map.put(String.valueOf(values[index]), values[index + 1]);
        return map;
    }

    private List<Object> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        return new ArrayList<>(readDocument(json).getOrDefault("items", List.of()) instanceof List<?> values ? values : List.of());
    }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of(); }
    private Object nullable(Object value) { return value == null || String.valueOf(value).isBlank() ? null : value; }
    private boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private long number(Object value, long fallback) { return value instanceof Number number ? number.longValue() : fallback; }
    private Timestamp timestamp(Object value) { return value == null || String.valueOf(value).isBlank() ? null : Timestamp.from(Instant.parse(String.valueOf(value))); }
    private String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }

    private Map<String, Object> readDocument(String json) {
        try { return objectMapper.readValue(json, MAP_TYPE); } catch (Exception exception) { throw new IllegalStateException("控制面 JSON 读取失败", exception); }
    }

    private String writeDocument(Map<String, Object> document) {
        try { return objectMapper.writeValueAsString(document); } catch (Exception exception) { throw new IllegalStateException("控制面 JSON 写入失败", exception); }
    }
}
