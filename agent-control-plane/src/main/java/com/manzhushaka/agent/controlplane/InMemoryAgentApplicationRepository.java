package com.manzhushaka.agent.controlplane;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Demo fallback backed by the same state machine as the JDBC source of truth. */
public final class InMemoryAgentApplicationRepository implements AgentApplicationRepository {
    private final Map<String, Map<String, Object>> applications = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> versions = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> bindings = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> publishRecords = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> apiKeys = new ConcurrentHashMap<>();
    private final AtomicLong versionSequence = new AtomicLong(0);

    @Override
    public synchronized List<Map<String, Object>> listApplications() {
        return applications.values().stream()
                .sorted(Comparator.comparing(value -> String.valueOf(value.get("updatedAt")), Comparator.reverseOrder()))
                .map(this::copy).toList();
    }

    @Override
    public synchronized Optional<Map<String, Object>> findApplication(String applicationId) {
        return Optional.ofNullable(applications.get(applicationId)).map(this::copy);
    }

    @Override
    public synchronized Optional<Map<String, Object>> findApplicationByCode(String code) {
        return applications.values().stream().filter(value -> code.equals(value.get("code"))).findFirst().map(this::copy);
    }

    @Override
    public synchronized void saveApplication(Map<String, Object> application, ControlPlaneAudit audit) {
        Map<String, Object> previous = applications.get(String.valueOf(application.get("id")));
        if (previous != null && !previous.get("code").equals(application.get("code"))
                && applications.values().stream().anyMatch(value -> !value.get("id").equals(application.get("id"))
                        && application.get("code").equals(value.get("code")))) {
            throw new IllegalArgumentException("应用编码已存在。");
        }
        applications.put(String.valueOf(application.get("id")), new LinkedHashMap<>(application));
    }

    @Override
    public synchronized boolean archiveApplication(String applicationId, Instant archivedAt, ControlPlaneAudit audit) {
        Map<String, Object> application = applications.get(applicationId);
        if (application == null || "ARCHIVED".equals(application.get("status"))) {
            return false;
        }
        boolean activeKey = apiKeys.values().stream().anyMatch(key -> applicationId.equals(key.get("applicationId"))
                && "ACTIVE".equals(key.get("status")));
        if (activeKey) {
            return false;
        }
        application.put("status", "ARCHIVED");
        application.put("updatedAt", archivedAt.toString());
        return true;
    }

    @Override
    public synchronized List<Map<String, Object>> listVersions(String applicationId) {
        return versions.values().stream()
                .filter(value -> applicationId.equals(value.get("applicationId")))
                .sorted(Comparator.comparingInt(value -> -((Number) value.get("version")).intValue()))
                .map(this::copy).toList();
    }

    @Override
    public synchronized Optional<Map<String, Object>> findVersion(String versionId) {
        return Optional.ofNullable(versions.get(versionId)).map(this::copy);
    }

    @Override
    public synchronized List<Map<String, Object>> listVersionBindings(String versionId) {
        return bindings.getOrDefault(versionId, List.of()).stream().map(this::copy).toList();
    }

    @Override
    public synchronized Map<String, Object> createVersion(
            Map<String, Object> version,
            List<Map<String, Object>> bindings,
            ControlPlaneAudit audit
    ) {
        Map<String, Object> application = applications.get(String.valueOf(version.get("applicationId")));
        if (application == null) {
            throw new IllegalArgumentException("应用不存在。");
        }
        if ("ARCHIVED".equals(application.get("status"))) {
            throw new IllegalStateException("已归档的应用不能创建版本。");
        }
        Map<String, Object> persisted = new LinkedHashMap<>(version);
        persisted.put("version", versionSequence.incrementAndGet());
        versions.put(String.valueOf(persisted.get("id")), persisted);
        this.bindings.put(String.valueOf(persisted.get("id")),
                bindings.stream().map(value -> (Map<String, Object>) new LinkedHashMap<>(value)).toList());
        return copy(persisted);
    }

    @Override
    public synchronized Map<String, Object> publishVersion(
            String applicationId,
            String versionId,
            String previousVersionId,
            ControlPlaneAudit audit
    ) {
        return changeStatus(applicationId, versionId, previousVersionId, "PUBLISH", audit);
    }

    @Override
    public synchronized Map<String, Object> rollbackVersion(
            String applicationId,
            String targetVersionId,
            String previousVersionId,
            ControlPlaneAudit audit
    ) {
        return changeStatus(applicationId, targetVersionId, previousVersionId, "ROLLBACK", audit);
    }

    private Map<String, Object> changeStatus(
            String applicationId,
            String versionId,
            String previousVersionId,
            String action,
            ControlPlaneAudit audit
    ) {
        Map<String, Object> application = applications.get(applicationId);
        if (application == null) {
            throw new IllegalArgumentException("应用不存在。");
        }
        if ("ARCHIVED".equals(application.get("status"))) {
            throw new IllegalStateException("已归档的应用不能发布或回滚。");
        }
        Map<String, Object> target = versions.get(versionId);
        if (target == null || !applicationId.equals(target.get("applicationId"))) {
            throw new IllegalArgumentException("版本不存在或不属于该应用。");
        }
        if ("PUBLISH".equals(action) && !"DRAFT".equals(target.get("status"))) {
            throw new IllegalStateException("只有草稿版本可以发布。");
        }
        if ("ROLLBACK".equals(action) && !"PUBLISHED".equals(target.get("status"))) {
            throw new IllegalStateException("只能回滚到已发布版本。");
        }
        Instant now = Instant.now();
        target.put("status", "PUBLISHED");
        target.put("publishedAt", target.get("publishedAt") == null ? now.toString() : target.get("publishedAt"));
        target.put("updatedAt", now.toString());
        Object previous = application.get("currentVersionId") == null ? previousVersionId : application.get("currentVersionId");
        application.put("currentVersionId", versionId);
        if ("PUBLISH".equals(action)) {
            // 发布版本即应用上线点：应用进入 ACTIVE，之后才允许 OpenAPI 与评估执行器调用。
            application.put("status", "ACTIVE");
        }
        application.put("updatedAt", now.toString());
        publishRecords.computeIfAbsent(applicationId, ignored -> new ArrayList<>()).add(mapOf(
                "id", audit.id(), "applicationId", applicationId, "versionId", versionId,
                "previousVersionId", previous, "action", action, "actor", audit.actor(),
                "createdAt", now.toString()));
        return copy(target);
    }

    @Override
    public synchronized List<Map<String, Object>> listPublishRecords(String applicationId) {
        return publishRecords.getOrDefault(applicationId, List.of()).stream()
                .sorted(Comparator.comparing(value -> String.valueOf(value.get("createdAt")), Comparator.reverseOrder()))
                .map(this::copy).toList();
    }

    @Override
    public synchronized List<Map<String, Object>> listApiKeys(String applicationId) {
        return apiKeys.values().stream()
                .filter(value -> applicationId.equals(value.get("applicationId")))
                .sorted(Comparator.comparing(value -> String.valueOf(value.get("createdAt")), Comparator.reverseOrder()))
                .map(this::copy).toList();
    }

    @Override
    public synchronized Optional<Map<String, Object>> findApiKey(String applicationId, String keyId) {
        return Optional.ofNullable(apiKeys.get(keyId))
                .filter(value -> applicationId.equals(value.get("applicationId")))
                .map(this::copy);
    }

    @Override
    public synchronized Optional<Map<String, Object>> findApiKeyByHash(String keyHash) {
        return apiKeys.values().stream().filter(value -> keyHash.equals(value.get("keyHash"))).findFirst().map(this::copy);
    }

    @Override
    public synchronized Map<String, Object> saveApiKey(Map<String, Object> apiKey, ControlPlaneAudit audit) {
        if (apiKeys.values().stream().anyMatch(value -> apiKey.get("keyHash").equals(value.get("keyHash")))) {
            throw new IllegalArgumentException("API Key 标识冲突，请重试。");
        }
        apiKeys.put(String.valueOf(apiKey.get("id")), new LinkedHashMap<>(apiKey));
        return copy(apiKey);
    }

    @Override
    public synchronized Optional<Map<String, Object>> revokeApiKey(
            String applicationId,
            String keyId,
            Instant revokedAt,
            ControlPlaneAudit audit
    ) {
        Map<String, Object> key = apiKeys.get(keyId);
        if (key == null || !applicationId.equals(key.get("applicationId")) || !"ACTIVE".equals(key.get("status"))) {
            return Optional.empty();
        }
        key.put("status", "REVOKED");
        key.put("revokedAt", revokedAt.toString());
        key.put("updatedAt", revokedAt.toString());
        return Optional.of(copy(key));
    }

    @Override
    public synchronized void recordApiKeyUsage(String keyId, Instant usedAt) {
        Map<String, Object> key = apiKeys.get(keyId);
        if (key != null && "ACTIVE".equals(key.get("status"))) {
            key.put("lastUsedAt", usedAt.toString());
            key.put("updatedAt", usedAt.toString());
        }
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        return Map.copyOf(new LinkedHashMap<>(value));
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
}
