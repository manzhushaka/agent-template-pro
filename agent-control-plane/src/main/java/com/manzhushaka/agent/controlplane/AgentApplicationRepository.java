package com.manzhushaka.agent.controlplane;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence boundary for Agent applications, immutable published versions, publish history and
 * hash-only API keys. Publish, rollback and version creation must be atomic per application.
 */
public interface AgentApplicationRepository {
    List<Map<String, Object>> listApplications();

    Optional<Map<String, Object>> findApplication(String applicationId);

    Optional<Map<String, Object>> findApplicationByCode(String code);

    void saveApplication(Map<String, Object> application, ControlPlaneAudit audit);

    /**
     * Archives the application when it has no active API keys. Returns false when the application
     * is already archived or still referenced by active keys.
     */
    boolean archiveApplication(String applicationId, Instant archivedAt, ControlPlaneAudit audit);

    List<Map<String, Object>> listVersions(String applicationId);

    Optional<Map<String, Object>> findVersion(String versionId);

    List<Map<String, Object>> listVersionBindings(String versionId);

    /** Creates the next draft version snapshot and its resource bindings in one atomic step. */
    Map<String, Object> createVersion(
            Map<String, Object> version,
            List<Map<String, Object>> bindings,
            ControlPlaneAudit audit
    );

    /** Publishes the draft version and records the publish history entry atomically. */
    Map<String, Object> publishVersion(
            String applicationId,
            String versionId,
            String previousVersionId,
            ControlPlaneAudit audit
    );

    /** Points the application at an already-published version and records a new publish history entry. */
    Map<String, Object> rollbackVersion(
            String applicationId,
            String targetVersionId,
            String previousVersionId,
            ControlPlaneAudit audit
    );

    List<Map<String, Object>> listPublishRecords(String applicationId);

    List<Map<String, Object>> listApiKeys(String applicationId);

    Optional<Map<String, Object>> findApiKey(String applicationId, String keyId);

    Optional<Map<String, Object>> findApiKeyByHash(String keyHash);

    Map<String, Object> saveApiKey(Map<String, Object> apiKey, ControlPlaneAudit audit);

    Optional<Map<String, Object>> revokeApiKey(String applicationId, String keyId, Instant revokedAt, ControlPlaneAudit audit);

    void recordApiKeyUsage(String keyId, Instant usedAt);
}
