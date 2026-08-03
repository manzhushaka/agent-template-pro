package com.manzhushaka.agent.controlplane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryControlPlaneRepository implements ControlPlaneRepository {
    private final Map<String, Map<String, Map<String, Object>>> documents = new ConcurrentHashMap<>();
    private final List<ControlPlaneAudit> audits = new ArrayList<>();
    private final Map<String, McpSyncLease> activeMcpSyncServers = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, Object>> listDocuments(String type) {
        return documents.getOrDefault(type, Map.of()).values().stream().map(Map::copyOf).toList();
    }

    @Override
    public void saveDocument(String type, Map<String, Object> document) {
        documents.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>()).put((String) document.get("id"), Map.copyOf(document));
    }

    @Override
    public synchronized void saveDocumentWithAudit(
            String type,
            Map<String, Object> document,
            ControlPlaneAudit audit
    ) {
        saveDocument(type, document);
        appendAudit(audit);
    }

    @Override
    public synchronized void saveMcpResourceWithAudit(String type, Map<String, Object> resource, ControlPlaneAudit audit) {
        if ("AGENT_TOOL_BINDING".equals(type)) {
            boolean exists = listDocuments(type).stream().anyMatch(existing ->
                    String.valueOf(resource.get("agentCode")).equals(existing.get("agentCode"))
                            && String.valueOf(resource.get("toolVersionId")).equals(existing.get("toolVersionId"))
            );
            if (exists) throw new McpDuplicateResourceException();
        }
        saveDocumentWithAudit(type, resource, audit);
    }

    @Override
    public synchronized Map<String, Object> createPromptVersion(
            Map<String, Object> version,
            ControlPlaneAudit audit
    ) {
        String promptId = String.valueOf(version.get("promptId"));
        int nextVersion = documents.getOrDefault("PROMPT_VERSION", Map.of()).values().stream()
                .filter(item -> promptId.equals(item.get("promptId")))
                .mapToInt(item -> ((Number) item.get("version")).intValue())
                .max()
                .orElse(0) + 1;
        Map<String, Object> persisted = new java.util.LinkedHashMap<>(version);
        persisted.put("version", nextVersion);
        saveDocument("PROMPT_VERSION", persisted);
        appendAudit(audit);
        return Map.copyOf(persisted);
    }

    @Override
    public synchronized Map<String, Object> publishPrompt(String promptId, String targetVersionId, ControlPlaneAudit audit) {
        Map<String, Object> current = documents.getOrDefault("PROMPT", Map.of()).get(promptId);
        if (current == null) {
            throw new IllegalArgumentException("资源不存在。");
        }
        Map<String, Object> updated = new java.util.LinkedHashMap<>(current);
        Object previous = updated.put("publishedVersionId", targetVersionId);
        updated.put("updatedAt", java.time.Instant.now().toString());
        saveDocument("PROMPT", updated);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(audit.metadata());
        if (previous != null && !String.valueOf(previous).isBlank()) {
            metadata.put("previousVersionId", previous);
        }
        appendAudit(new ControlPlaneAudit(audit.id(), audit.actor(), audit.action(), audit.resourceType(), audit.resourceId(), metadata, audit.createdAt()));
        return Map.copyOf(updated);
    }

    @Override
    public synchronized void appendAudit(ControlPlaneAudit audit) {
        audits.add(audit);
    }

    @Override
    public synchronized List<ControlPlaneAudit> listAudits() {
        return List.copyOf(audits);
    }

    @Override
    public Set<String> permissionsForRole(String role) {
        return switch (role) {
            case "ADMIN" -> ControlPlaneService.allPermissions();
            case "OPERATOR" -> ControlPlaneService.operatorPermissions();
            case "VIEWER" -> ControlPlaneService.viewerPermissions();
            default -> Set.of();
        };
    }

    @Override
    public boolean claimMcpSync(
            String syncId,
            String serverId,
            java.time.Instant startedAt,
            java.time.Instant leaseUntil
    ) {
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        activeMcpSyncServers.compute(serverId, (ignored, existing) -> {
            if (existing == null || !existing.leaseUntil().isAfter(startedAt)) {
                claimed.set(true);
                return new McpSyncLease(syncId, leaseUntil);
            }
            return existing;
        });
        return claimed.get();
    }

    @Override
    public void finishMcpSync(String syncId, String status, int toolCount, int versionCount, String errorCode, java.time.Instant finishedAt) {
        activeMcpSyncServers.entrySet().removeIf(entry -> syncId.equals(entry.getValue().syncId()));
    }

    private record McpSyncLease(String syncId, java.time.Instant leaseUntil) { }
}
