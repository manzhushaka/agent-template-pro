package com.manzhushaka.agent.controlplane;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ControlPlaneRepository {
    List<Map<String, Object>> listDocuments(String type);

    void saveDocument(String type, Map<String, Object> document);

    void saveDocumentWithAudit(String type, Map<String, Object> document, ControlPlaneAudit audit);

    Map<String, Object> createPromptVersion(Map<String, Object> version, ControlPlaneAudit audit);

    Map<String, Object> publishPrompt(String promptId, String targetVersionId, ControlPlaneAudit audit);

    void appendAudit(ControlPlaneAudit audit);

    List<ControlPlaneAudit> listAudits();

    Set<String> permissionsForRole(String role);

    /** MCP resources use dedicated tables in JDBC implementations; Demo keeps the document fallback. */
    default List<Map<String, Object>> listMcpResources(String type) {
        return listDocuments(type);
    }

    default void saveMcpResourceWithAudit(String type, Map<String, Object> resource, ControlPlaneAudit audit) {
        saveDocumentWithAudit(type, resource, audit);
    }

    /** Returns false when another sync for the same server still owns the database lease. */
    default boolean claimMcpSync(
            String syncId,
            String serverId,
            java.time.Instant startedAt,
            java.time.Instant leaseUntil
    ) {
        return true;
    }

    default void finishMcpSync(String syncId, String status, int toolCount, int versionCount, String errorCode, java.time.Instant finishedAt) {
    }
}
