package com.manzhushaka.agent.controlplane;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe development repository that preserves the JDBC repository's lease semantics. */
public final class InMemoryKnowledgeRepository implements KnowledgeRepository {
    private final Map<String, Map<String, Object>> bases = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> documents = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> versions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> chunks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> objectCleanups = new ConcurrentHashMap<>();
    private final List<ControlPlaneAudit> audits = new ArrayList<>();

    @Override public List<Map<String, Object>> listKnowledgeBases() { return values(bases); }
    @Override public Optional<Map<String, Object>> findKnowledgeBase(String id) { return copy(bases.get(id)); }

    @Override public synchronized void saveKnowledgeBase(Map<String, Object> base, ControlPlaneAudit audit) {
        bases.put(id(base), immutable(base)); append(audit);
    }

    @Override public List<Map<String, Object>> listDocuments(String knowledgeBaseId, boolean includeDeleted) {
        return values(documents).stream()
                .filter(value -> knowledgeBaseId.equals(value.get("knowledgeBaseId")))
                .filter(value -> includeDeleted || value.get("deletedAt") == null)
                .toList();
    }

    @Override public Optional<Map<String, Object>> findDocument(String id) { return copy(documents.get(id)); }
    @Override public Optional<Map<String, Object>> findDocumentVersion(String id) { return copy(versions.get(id)); }
    @Override public List<Map<String, Object>> listDocumentVersions(String documentId) { return values(versions).stream().filter(value -> documentId.equals(value.get("documentId"))).toList(); }

    @Override public synchronized void createDocumentVersionAndJob(Map<String, Object> document, Map<String, Object> version, Map<String, Object> job, String objectCleanupId, ControlPlaneAudit audit) {
        documents.put(id(document), immutable(document)); versions.put(id(version), immutable(version)); jobs.put(id(job), queuedJob(job)); disarmObjectCleanup(objectCleanupId); append(audit);
    }

    @Override public synchronized void createNextVersionAndJob(String documentId, Map<String, Object> version, Map<String, Object> job, String objectCleanupId, Instant updatedAt, ControlPlaneAudit audit) {
        Map<String, Object> document = documents.get(documentId);
        if (document == null || document.get("deletedAt") != null) throw new IllegalArgumentException("文档不存在。");
        documents.put(documentId, update(document, "currentVersionId", version.get("id"), "status", "QUEUED", "updatedAt", updatedAt.toString()));
        versions.put(id(version), immutable(version)); jobs.put(id(job), queuedJob(job)); disarmObjectCleanup(objectCleanupId); append(audit);
    }

    @Override public synchronized List<Map<String, Object>> claimIndexJobs(String owner, Instant now, Instant leaseUntil, int limit) {
        cancelInvalidJobs(now);
        List<Map<String, Object>> claimed = new ArrayList<>();
        for (Map<String, Object> job : values(jobs).stream().sorted(Comparator.comparing(value -> String.valueOf(value.get("createdAt")))).toList()) {
            boolean claimable = "QUEUED".equals(job.get("status")) && !Instant.parse(String.valueOf(job.get("nextAttemptAt"))).isAfter(now)
                    || "RUNNING".equals(job.get("status")) && job.get("leaseUntil") != null && !Instant.parse(String.valueOf(job.get("leaseUntil"))).isAfter(now);
            if (!claimable || claimed.size() >= limit) continue;
            Map<String, Object> updated = new LinkedHashMap<>(job);
            String leaseOwner = uniqueLeaseOwner(owner);
            updated.put("status", "RUNNING"); updated.put("leaseOwner", leaseOwner); updated.put("leaseToken", UUID.randomUUID().toString());
            updated.put("leaseEpoch", ((Number) job.getOrDefault("leaseEpoch", 0L)).longValue() + 1);
            updated.put("leaseUntil", leaseUntil.toString()); updated.put("updatedAt", now.toString());
            jobs.put(id(updated), immutable(updated)); claimed.add(immutable(updated));
        }
        return List.copyOf(claimed);
    }

    @Override public synchronized boolean completeIndexJob(String jobId, String owner, String token, List<Map<String, Object>> replacement, Instant completedAt, ControlPlaneAudit audit) {
        return completeIndexJobWithVectorMutation(jobId, owner, token, replacement, () -> { }, completedAt, audit);
    }

    @Override public synchronized boolean completeIndexJobWithVectorMutation(String jobId, String owner, String token, List<Map<String, Object>> replacement, Runnable vectorMutation, Instant completedAt, ControlPlaneAudit audit) {
        Map<String, Object> job = jobs.get(jobId);
        if (!owned(job, owner, token, completedAt) || !current(job)) return false;
        vectorMutation.run();
        chunks.entrySet().removeIf(entry -> job.get("documentVersionId").equals(entry.getValue().get("documentVersionId")));
        replacement.forEach(chunk -> chunks.put(id(chunk), immutable(chunk)));
        Map<String, Object> version = versions.get(job.get("documentVersionId"));
        Map<String, Object> document = documents.get(job.get("documentId"));
        versions.put(id(version), update(version, "status", "INDEXED", "indexedAt", completedAt.toString()));
        documents.put(id(document), update(document, "status", "INDEXED", "updatedAt", completedAt.toString()));
        jobs.put(jobId, update(job, "status", "SUCCEEDED", "activeJobKey", null, "leaseOwner", null, "leaseToken", null, "leaseUntil", null, "finishedAt", completedAt.toString(), "updatedAt", completedAt.toString()));
        append(audit); return true;
    }

    @Override public synchronized boolean failIndexJob(String jobId, String owner, String token, String errorCode, Instant retryAt, Instant failedAt, ControlPlaneAudit audit) {
        Map<String, Object> job = jobs.get(jobId);
        if (!owned(job, owner, token, failedAt)) return false;
        jobs.put(jobId, update(job, "status", "FAILED", "activeJobKey", null, "attempts", ((Number) job.get("attempts")).intValue() + 1,
                "lastErrorCode", errorCode, "nextAttemptAt", retryAt.toString(), "leaseOwner", null, "leaseToken", null, "leaseUntil", null, "updatedAt", failedAt.toString()));
        append(audit); return true;
    }

    @Override public synchronized Optional<Map<String, Object>> retryIndexJob(String jobId, Instant retriedAt, ControlPlaneAudit audit) {
        Map<String, Object> job = jobs.get(jobId);
        if (job == null || !"FAILED".equals(job.get("status"))) return Optional.empty();
        Map<String, Object> updated = update(job, "activeJobKey", job.get("documentVersionId"), "status", "QUEUED", "nextAttemptAt", retriedAt.toString(), "leaseOwner", null, "leaseToken", null, "leaseUntil", null, "lastErrorCode", null, "updatedAt", retriedAt.toString());
        jobs.put(jobId, updated); append(audit); return Optional.of(updated);
    }

    @Override public List<Map<String, Object>> listIndexJobs(String knowledgeBaseId) {
        return values(jobs).stream().filter(value -> knowledgeBaseId == null || knowledgeBaseId.isBlank() || knowledgeBaseId.equals(value.get("knowledgeBaseId")))
                .sorted(Comparator.comparing(value -> String.valueOf(value.get("createdAt")), Comparator.reverseOrder())).toList();
    }

    @Override public List<Map<String, Object>> listChunks(String kb, String documentId, String versionId) {
        return values(chunks).stream().filter(value -> kb.equals(value.get("knowledgeBaseId")))
                .filter(value -> documentId == null || documentId.equals(value.get("documentId")))
                .filter(value -> versionId == null || versionId.equals(value.get("documentVersionId")))
                .filter(this::visibleCurrentChunk)
                .sorted(Comparator.comparingInt(value -> ((Number) value.get("chunkIndex")).intValue())).toList();
    }

    @Override public Optional<Map<String, Object>> findChunk(String chunkId) { return copy(chunks.get(chunkId)); }

    @Override public synchronized boolean setChunkEnabled(String chunkId, boolean enabled, Instant now, ControlPlaneAudit audit) {
        Map<String, Object> chunk = chunks.get(chunkId); if (chunk == null) return false;
        chunks.put(chunkId, update(chunk, "enabled", enabled, "updatedAt", now.toString())); append(audit); return true;
    }

    @Override public synchronized boolean setChunkEnabledAndEnqueueVectorSync(String chunkId, boolean enabled, Map<String, Object> cleanup, Instant now, ControlPlaneAudit audit) {
        Map<String, Object> chunk = chunks.get(chunkId); if (chunk == null) return false;
        chunks.put(chunkId, update(chunk, "enabled", enabled, "updatedAt", now.toString()));
        objectCleanups.put(id(cleanup), immutable(cleanup));
        append(audit);
        return true;
    }

    @Override public synchronized Optional<Map<String, Object>> markDocumentDeleted(String documentId, Instant now, ControlPlaneAudit audit) {
        Map<String, Object> document = documents.get(documentId); if (document == null || document.get("deletedAt") != null) return Optional.empty();
        Map<String, Object> updated = update(document, "status", "DELETED", "deletedAt", now.toString(), "updatedAt", now.toString());
        documents.put(documentId, updated);
        chunks.entrySet().removeIf(entry -> documentId.equals(entry.getValue().get("documentId")));
        cancelInvalidJobs(now);
        append(audit);
        return Optional.of(updated);
    }

    @Override public List<Map<String, Object>> listDeletedDocumentVersionsAwaitingCompensation(int limit) {
        return values(versions).stream().filter(value -> value.get("objectDeletedAt") == null)
                .filter(value -> documents.containsKey(value.get("documentId")) && documents.get(value.get("documentId")).get("deletedAt") != null)
                .limit(limit).toList();
    }

    @Override public synchronized boolean markVersionObjectDeleted(String versionId, Instant now, ControlPlaneAudit audit) {
        Map<String, Object> version = versions.get(versionId); if (version == null || version.get("objectDeletedAt") != null) return false;
        versions.put(versionId, update(version, "objectDeletedAt", now.toString(), "updatedAt", now.toString())); append(audit); return true;
    }

    @Override public synchronized void enqueueObjectCleanup(Map<String, Object> cleanup, ControlPlaneAudit audit) {
        String objectKey = String.valueOf(cleanup.get("objectKey"));
        if (objectCleanups.values().stream().anyMatch(value -> objectKey.equals(value.get("objectKey")))) return;
        objectCleanups.put(id(cleanup), immutable(cleanup));
        append(audit);
    }

    @Override public synchronized void activateObjectCleanup(String cleanupId, Instant activatedAt) {
        Map<String, Object> cleanup = objectCleanups.get(cleanupId);
        if (cleanup == null || "SUCCEEDED".equals(cleanup.get("status"))) return;
        objectCleanups.put(cleanupId, update(cleanup, "status", "PENDING", "updatedAt", activatedAt.toString()));
    }

    @Override public List<Map<String, Object>> listObjectCleanupCandidates(int limit) {
        return values(objectCleanups).stream()
                .filter(value -> "PENDING".equals(value.get("status")) || ("PREPARED".equals(value.get("status"))
                        && !Instant.parse(String.valueOf(value.get("createdAt"))).isAfter(Instant.now().minus(Duration.ofMinutes(5)))))
                .sorted(Comparator.comparing(value -> String.valueOf(value.get("createdAt"))))
                .limit(limit)
                .toList();
    }

    @Override public synchronized boolean completeObjectCleanup(String cleanupId, Instant completedAt, ControlPlaneAudit audit) {
        Map<String, Object> cleanup = objectCleanups.get(cleanupId);
        if (cleanup == null || (!"PENDING".equals(cleanup.get("status")) && !"PREPARED".equals(cleanup.get("status")))) return false;
        objectCleanups.put(cleanupId, update(cleanup, "status", "SUCCEEDED", "completedAt", completedAt.toString(), "updatedAt", completedAt.toString()));
        append(audit);
        return true;
    }

    @Override public synchronized void recordObjectCleanupFailure(String cleanupId, String errorCode, Instant failedAt) {
        Map<String, Object> cleanup = objectCleanups.get(cleanupId);
        if (cleanup == null || !"PENDING".equals(cleanup.get("status"))) return;
        objectCleanups.put(cleanupId, update(cleanup, "attempts", ((Number) cleanup.getOrDefault("attempts", 0)).intValue() + 1,
                "lastErrorCode", errorCode, "updatedAt", failedAt.toString()));
    }

    private boolean owned(Map<String, Object> job, String owner, String token, Instant now) {
        return job != null && "RUNNING".equals(job.get("status")) && owner.equals(job.get("leaseOwner"))
                && token.equals(job.get("leaseToken")) && job.get("leaseUntil") != null
                && Instant.parse(String.valueOf(job.get("leaseUntil"))).isAfter(now);
    }

    private boolean current(Map<String, Object> job) {
        Map<String, Object> document = documents.get(job.get("documentId"));
        Map<String, Object> base = bases.get(job.get("knowledgeBaseId"));
        return base != null && "ACTIVE".equals(base.get("status")) && document != null && document.get("deletedAt") == null
                && job.get("documentVersionId").equals(document.get("currentVersionId"));
    }

    private boolean visibleCurrentChunk(Map<String, Object> chunk) {
        Map<String, Object> document = documents.get(chunk.get("documentId"));
        return document != null && document.get("deletedAt") == null && chunk.get("documentVersionId").equals(document.get("currentVersionId"));
    }

    private void cancelInvalidJobs(Instant now) {
        for (Map.Entry<String, Map<String, Object>> entry : jobs.entrySet()) {
            Map<String, Object> job = entry.getValue();
            if (("QUEUED".equals(job.get("status")) || "RUNNING".equals(job.get("status"))) && !current(job)) {
                jobs.put(entry.getKey(), update(job, "status", "CANCELLED", "activeJobKey", null, "leaseOwner", null, "leaseToken", null,
                        "leaseUntil", null, "finishedAt", now.toString(), "updatedAt", now.toString()));
            }
        }
    }

    private String uniqueLeaseOwner(String workerId) {
        String prefix = workerId == null || workerId.isBlank() ? "knowledge-worker" : workerId;
        prefix = prefix.length() > 91 ? prefix.substring(0, 91) : prefix;
        return prefix + "-" + UUID.randomUUID();
    }

    private Map<String, Object> queuedJob(Map<String, Object> job) {
        return update(job, "activeJobKey", job.get("documentVersionId"), "leaseEpoch", 0L);
    }
    private void disarmObjectCleanup(String cleanupId) {
        if (cleanupId == null) return;
        Map<String, Object> cleanup = objectCleanups.get(cleanupId);
        if (cleanup == null || !"PREPARED".equals(cleanup.get("status"))) throw new IllegalStateException("OBJECT_CLEANUP_INTENT_MISSING");
        objectCleanups.put(cleanupId, update(cleanup, "status", "SUCCEEDED", "completedAt", Instant.now().toString(), "updatedAt", Instant.now().toString()));
    }
    private List<Map<String, Object>> values(Map<String, Map<String, Object>> source) { return source.values().stream().map(InMemoryKnowledgeRepository::immutable).toList(); }
    private Optional<Map<String, Object>> copy(Map<String, Object> value) { return Optional.ofNullable(value).map(InMemoryKnowledgeRepository::immutable); }
    private static Map<String, Object> immutable(Map<String, Object> value) { return Map.copyOf(value); }
    private static String id(Map<String, Object> value) { return String.valueOf(value.get("id")); }
    private static Map<String, Object> update(Map<String, Object> value, Object... updates) { Map<String, Object> result = new LinkedHashMap<>(value); for (int i = 0; i < updates.length; i += 2) { if (updates[i + 1] == null) result.remove(String.valueOf(updates[i])); else result.put(String.valueOf(updates[i]), updates[i + 1]); } return immutable(result); }
    private void append(ControlPlaneAudit audit) { audits.add(audit); }
}
