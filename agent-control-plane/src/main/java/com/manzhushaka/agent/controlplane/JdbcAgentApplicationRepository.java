package com.manzhushaka.agent.controlplane;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC source of truth for Agent applications, immutable published versions, publish history
 * and hash-only API keys. Publish, rollback and version creation run in one transaction each.
 */
public final class JdbcAgentApplicationRepository implements AgentApplicationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcAgentApplicationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.json = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<Map<String, Object>> listApplications() {
        return jdbc.query("SELECT * FROM agent_application ORDER BY updated_at DESC", (rs, row) -> application(rs));
    }

    @Override
    public Optional<Map<String, Object>> findApplication(String applicationId) {
        return jdbc.query("SELECT * FROM agent_application WHERE id = ?", (rs, row) -> application(rs), applicationId)
                .stream().findFirst();
    }

    @Override
    public Optional<Map<String, Object>> findApplicationByCode(String code) {
        return jdbc.query("SELECT * FROM agent_application WHERE code = ?", (rs, row) -> application(rs), code)
                .stream().findFirst();
    }

    @Override
    public void saveApplication(Map<String, Object> application, ControlPlaneAudit audit) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_application SET code=?,display_name=?,description_text=?,status=?,current_version_id=?,updated_at=? WHERE id=?",
                    application.get("code"), application.get("displayName"), nullable(application.get("description")),
                    application.get("status"), nullable(application.get("currentVersionId")),
                    Timestamp.from(Instant.now()), application.get("id"));
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_application(id,code,display_name,description_text,status,current_version_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                        application.get("id"), application.get("code"), application.get("displayName"),
                        nullable(application.get("description")), application.get("status"),
                        nullable(application.get("currentVersionId")), time(application.get("createdAt")),
                        time(application.get("updatedAt")));
            }
            appendAudit(audit);
        });
    }

    @Override
    public boolean archiveApplication(String applicationId, Instant archivedAt, ControlPlaneAudit audit) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int changed = jdbc.update(
                    "UPDATE agent_application SET status='ARCHIVED',updated_at=? WHERE id=? AND status<>'ARCHIVED'"
                            + " AND NOT EXISTS (SELECT 1 FROM agent_api_key k WHERE k.application_id=agent_application.id AND k.status='ACTIVE')",
                    Timestamp.from(archivedAt), applicationId);
            if (changed == 0) {
                return false;
            }
            appendAudit(audit);
            return true;
        }));
    }

    @Override
    public List<Map<String, Object>> listVersions(String applicationId) {
        return jdbc.query("SELECT * FROM agent_application_version WHERE application_id=? ORDER BY version_no DESC",
                (rs, row) -> version(rs), applicationId);
    }

    @Override
    public Optional<Map<String, Object>> findVersion(String versionId) {
        return jdbc.query("SELECT * FROM agent_application_version WHERE id=?", (rs, row) -> version(rs), versionId)
                .stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> listVersionBindings(String versionId) {
        return jdbc.query("SELECT * FROM agent_application_binding WHERE version_id=? ORDER BY resource_type, resource_id",
                (rs, row) -> binding(rs), versionId);
    }

    @Override
    public Map<String, Object> createVersion(Map<String, Object> version, List<Map<String, Object>> bindings, ControlPlaneAudit audit) {
        try {
            return tx.execute(status -> {
                Map<String, Object> locked = jdbc.query(
                        "SELECT * FROM agent_application WHERE id=? FOR UPDATE", (rs, row) -> application(rs),
                        version.get("applicationId")).stream().findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("应用不存在。"));
                if ("ARCHIVED".equals(locked.get("status"))) {
                    throw new IllegalStateException("已归档的应用不能创建版本。");
                }
                Integer current = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version_no),0) FROM agent_application_version WHERE application_id=?",
                        Integer.class, version.get("applicationId"));
                Map<String, Object> persisted = new LinkedHashMap<>(version);
                persisted.put("version", (current == null ? 0 : current) + 1);
                jdbc.update(
                        "INSERT INTO agent_application_version(id,application_id,version_no,status,model_code,prompt_id,prompt_version_id,knowledge_base_id,config_json,created_by,published_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?)",
                        persisted.get("id"), persisted.get("applicationId"), persisted.get("version"),
                        persisted.get("status"), persisted.get("modelCode"), persisted.get("promptId"),
                        persisted.get("promptVersionId"), nullable(persisted.get("knowledgeBaseId")),
                        json(persisted.get("config")), persisted.get("createdBy"), null,
                        time(persisted.get("createdAt")), time(persisted.get("updatedAt")));
                for (Map<String, Object> binding : bindings) {
                    jdbc.update(
                            "INSERT INTO agent_application_binding(id,version_id,resource_type,resource_id,resource_version,config_json,created_at) VALUES(?,?,?,?,?,CAST(? AS JSON),?)",
                            binding.get("id"), persisted.get("id"), binding.get("resourceType"),
                            binding.get("resourceId"), nullable(binding.get("resourceVersion")),
                            json(binding.get("config")), time(binding.get("createdAt")));
                }
                appendAudit(audit);
                return Map.copyOf(persisted);
            });
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("应用版本或资源绑定已存在。");
        }
    }

    @Override
    public Map<String, Object> publishVersion(
            String applicationId,
            String versionId,
            String previousVersionId,
            ControlPlaneAudit audit
    ) {
        return changeVersionStatus(applicationId, versionId, previousVersionId, "PUBLISH", audit);
    }

    @Override
    public Map<String, Object> rollbackVersion(
            String applicationId,
            String targetVersionId,
            String previousVersionId,
            ControlPlaneAudit audit
    ) {
        return changeVersionStatus(applicationId, targetVersionId, previousVersionId, "ROLLBACK", audit);
    }

    private Map<String, Object> changeVersionStatus(
            String applicationId,
            String versionId,
            String previousVersionId,
            String action,
            ControlPlaneAudit audit
    ) {
        return tx.execute(status -> {
            Map<String, Object> app = jdbc.query("SELECT * FROM agent_application WHERE id=? FOR UPDATE",
                    (rs, row) -> application(rs), applicationId).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("应用不存在。"));
            if ("ARCHIVED".equals(app.get("status"))) {
                throw new IllegalStateException("已归档的应用不能发布或回滚。");
            }
            Map<String, Object> target = jdbc.query(
                    "SELECT * FROM agent_application_version WHERE id=? AND application_id=? FOR UPDATE",
                    (rs, row) -> version(rs), versionId, applicationId).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("版本不存在或不属于该应用。"));
            if ("PUBLISH".equals(action)) {
                if (!"DRAFT".equals(target.get("status"))) {
                    throw new IllegalStateException("只有草稿版本可以发布。");
                }
            } else {
                if (!"PUBLISHED".equals(target.get("status"))) {
                    throw new IllegalStateException("只能回滚到已发布版本。");
                }
            }
            Instant now = Instant.now();
            String previous = String.valueOf(app.get("currentVersionId"));
            if (previous == null || previous.isBlank() || "null".equals(previous)) {
                previous = previousVersionId;
            }
            int changed = jdbc.update(
                    "UPDATE agent_application_version SET status='PUBLISHED',published_at=COALESCE(published_at,?),updated_at=? WHERE id=?",
                    Timestamp.from(now), Timestamp.from(now), versionId);
            if (changed == 0) {
                throw new IllegalStateException("版本状态更新失败。");
            }
            jdbc.update("UPDATE agent_application SET current_version_id=?,updated_at=? WHERE id=?",
                    versionId, Timestamp.from(now), applicationId);
            if ("PUBLISH".equals(action)) {
                // 发布版本即应用上线点：应用进入 ACTIVE，之后才允许 OpenAPI 与评估执行器调用。
                jdbc.update("UPDATE agent_application SET status='ACTIVE',updated_at=? WHERE id=? AND status='DRAFT'",
                        Timestamp.from(now), applicationId);
            }
            jdbc.update(
                    "INSERT INTO agent_application_publish_record(id,application_id,version_id,previous_version_id,action,actor,created_at) VALUES(?,?,?,?,?,?,?)",
                    audit.id(), applicationId, versionId, nullable(previous), action, audit.actor(), Timestamp.from(now));
            appendAudit(audit);
            return jdbc.query("SELECT * FROM agent_application_version WHERE id=?", (rs, row) -> version(rs), versionId)
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("版本状态更新后读取失败。"));
        });
    }

    @Override
    public List<Map<String, Object>> listPublishRecords(String applicationId) {
        return jdbc.query("SELECT * FROM agent_application_publish_record WHERE application_id=? ORDER BY created_at DESC",
                (rs, row) -> publishRecord(rs), applicationId);
    }

    @Override
    public List<Map<String, Object>> listApiKeys(String applicationId) {
        return jdbc.query("SELECT * FROM agent_api_key WHERE application_id=? ORDER BY created_at DESC",
                (rs, row) -> apiKey(rs), applicationId);
    }

    @Override
    public Optional<Map<String, Object>> findApiKey(String applicationId, String keyId) {
        return jdbc.query("SELECT * FROM agent_api_key WHERE id=? AND application_id=?",
                (rs, row) -> apiKey(rs), keyId, applicationId).stream().findFirst();
    }

    @Override
    public Optional<Map<String, Object>> findApiKeyByHash(String keyHash) {
        return jdbc.query("SELECT * FROM agent_api_key WHERE key_hash=?", (rs, row) -> apiKey(rs), keyHash)
                .stream().findFirst();
    }

    @Override
    public Map<String, Object> saveApiKey(Map<String, Object> apiKey, ControlPlaneAudit audit) {
        try {
            return tx.execute(status -> {
                jdbc.update(
                        "INSERT INTO agent_api_key(id,application_id,key_hash,key_prefix,status,scopes_json,expires_at,last_used_at,revoked_at,created_at,updated_at) VALUES(?,?,?,?,?,CAST(? AS JSON),?,?,?,?,?)",
                        apiKey.get("id"), apiKey.get("applicationId"), apiKey.get("keyHash"), apiKey.get("keyPrefix"),
                        apiKey.get("status"), json(apiKey.get("scopes")), time(apiKey.get("expiresAt")),
                        time(apiKey.get("lastUsedAt")), time(apiKey.get("revokedAt")),
                        time(apiKey.get("createdAt")), time(apiKey.get("updatedAt")));
                appendAudit(audit);
                return Map.copyOf(apiKey);
            });
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("API Key 标识冲突，请重试。");
        }
    }

    @Override
    public Optional<Map<String, Object>> revokeApiKey(String applicationId, String keyId, Instant revokedAt, ControlPlaneAudit audit) {
        return tx.execute(status -> {
            int changed = jdbc.update(
                    "UPDATE agent_api_key SET status='REVOKED',revoked_at=?,updated_at=? WHERE id=? AND application_id=? AND status='ACTIVE'",
                    Timestamp.from(revokedAt), Timestamp.from(revokedAt), keyId, applicationId);
            if (changed == 0) {
                return Optional.empty();
            }
            appendAudit(audit);
            return jdbc.query("SELECT * FROM agent_api_key WHERE id=?", (rs, row) -> apiKey(rs), keyId)
                    .stream().findFirst();
        });
    }

    @Override
    public void recordApiKeyUsage(String keyId, Instant usedAt) {
        jdbc.update("UPDATE agent_api_key SET last_used_at=?,updated_at=? WHERE id=? AND status='ACTIVE'",
                Timestamp.from(usedAt), Timestamp.from(usedAt), keyId);
    }

    private void appendAudit(ControlPlaneAudit audit) {
        jdbc.update(
                "INSERT INTO agent_control_plane_audit(id,actor_username,action_code,resource_type,resource_id,metadata_json,created_at) VALUES(?,?,?,?,?,CAST(? AS JSON),?)",
                audit.id(), audit.actor(), audit.action(), audit.resourceType(), audit.resourceId(),
                json(audit.metadata()), Timestamp.from(audit.createdAt()));
    }

    private Map<String, Object> application(java.sql.ResultSet rs) throws java.sql.SQLException {
        return mapOf(
                "id", rs.getString("id"), "code", rs.getString("code"), "displayName", rs.getString("display_name"),
                "description", rs.getString("description_text"), "status", rs.getString("status"),
                "currentVersionId", rs.getString("current_version_id"), "createdAt", instant(rs.getTimestamp("created_at")),
                "updatedAt", instant(rs.getTimestamp("updated_at")));
    }

    private Map<String, Object> version(java.sql.ResultSet rs) throws java.sql.SQLException {
        return mapOf(
                "id", rs.getString("id"), "applicationId", rs.getString("application_id"),
                "version", rs.getInt("version_no"), "status", rs.getString("status"),
                "modelCode", rs.getString("model_code"), "promptId", rs.getString("prompt_id"),
                "promptVersionId", rs.getString("prompt_version_id"), "knowledgeBaseId", rs.getString("knowledge_base_id"),
                "config", readMap(rs.getString("config_json")), "createdBy", rs.getString("created_by"),
                "publishedAt", instant(rs.getTimestamp("published_at")), "createdAt", instant(rs.getTimestamp("created_at")),
                "updatedAt", instant(rs.getTimestamp("updated_at")));
    }

    private Map<String, Object> binding(java.sql.ResultSet rs) throws java.sql.SQLException {
        return mapOf(
                "id", rs.getString("id"), "versionId", rs.getString("version_id"),
                "resourceType", rs.getString("resource_type"), "resourceId", rs.getString("resource_id"),
                "resourceVersion", rs.getString("resource_version"), "config", readMap(rs.getString("config_json")),
                "createdAt", instant(rs.getTimestamp("created_at")));
    }

    private Map<String, Object> publishRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return mapOf(
                "id", rs.getString("id"), "applicationId", rs.getString("application_id"),
                "versionId", rs.getString("version_id"), "previousVersionId", rs.getString("previous_version_id"),
                "action", rs.getString("action"), "actor", rs.getString("actor"),
                "createdAt", instant(rs.getTimestamp("created_at")));
    }

    private Map<String, Object> apiKey(java.sql.ResultSet rs) throws java.sql.SQLException {
        return mapOf(
                "id", rs.getString("id"), "applicationId", rs.getString("application_id"),
                "keyPrefix", rs.getString("key_prefix"), "status", rs.getString("status"),
                "scopes", readList(rs.getString("scopes_json")), "expiresAt", instant(rs.getTimestamp("expires_at")),
                "lastUsedAt", instant(rs.getTimestamp("last_used_at")), "revokedAt", instant(rs.getTimestamp("revoked_at")),
                "createdAt", instant(rs.getTimestamp("created_at")), "updatedAt", instant(rs.getTimestamp("updated_at")));
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 应用 JSON 写入失败", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 应用 JSON 读取失败", exception);
        }
    }

    private List<?> readList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, List.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 应用 JSON 读取失败", exception);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return Map.copyOf(result);
    }

    private Object nullable(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : value;
    }

    private Timestamp time(Object value) {
        return value == null ? null : Timestamp.from(Instant.parse(String.valueOf(value)));
    }

    private String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }
}
