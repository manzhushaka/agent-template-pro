package com.manzhushaka.agent.controlplane;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dedicated persistence boundary for knowledge metadata and index work. */
public interface KnowledgeRepository {
    List<Map<String, Object>> listKnowledgeBases();

    Optional<Map<String, Object>> findKnowledgeBase(String id);

    void saveKnowledgeBase(Map<String, Object> knowledgeBase, ControlPlaneAudit audit);

    List<Map<String, Object>> listDocuments(String knowledgeBaseId, boolean includeDeleted);

    Optional<Map<String, Object>> findDocument(String documentId);

    Optional<Map<String, Object>> findDocumentVersion(String versionId);

    List<Map<String, Object>> listDocumentVersions(String documentId);

    void createDocumentVersionAndJob(
            Map<String, Object> document,
            Map<String, Object> version,
            Map<String, Object> job,
            String objectCleanupId,
            ControlPlaneAudit audit
    );

    void createNextVersionAndJob(
            String documentId,
            Map<String, Object> version,
            Map<String, Object> job,
            String objectCleanupId,
            Instant updatedAt,
            ControlPlaneAudit audit
    );

    /**
     * Claims work using a fresh owner and fencing token for every job. Implementations must never
     * reuse either value when an expired job is reclaimed.
     */
    List<Map<String, Object>> claimIndexJobs(String workerId, Instant now, Instant leaseUntil, int limit);

    boolean completeIndexJob(
            String jobId,
            String leaseOwner,
            String leaseToken,
            List<Map<String, Object>> chunks,
            Instant completedAt,
            ControlPlaneAudit audit
    );

    /**
     * Atomically verifies the current lease, applies a precomputed vector mutation, writes chunks
     * and completes the job. The callback must only contain local database mutations and shares
     * this repository transaction in the JDBC implementation.
     */
    boolean completeIndexJobWithVectorMutation(
            String jobId,
            String leaseOwner,
            String leaseToken,
            List<Map<String, Object>> chunks,
            Runnable vectorMutation,
            Instant completedAt,
            ControlPlaneAudit audit
    );

    boolean failIndexJob(
            String jobId,
            String leaseOwner,
            String leaseToken,
            String errorCode,
            Instant retryAt,
            Instant failedAt,
            ControlPlaneAudit audit
    );

    Optional<Map<String, Object>> retryIndexJob(String jobId, Instant retriedAt, ControlPlaneAudit audit);

    List<Map<String, Object>> listIndexJobs(String knowledgeBaseId);

    List<Map<String, Object>> listChunks(String knowledgeBaseId, String documentId, String documentVersionId);

    Optional<Map<String, Object>> findChunk(String chunkId);

    boolean setChunkEnabled(String chunkId, boolean enabled, Instant updatedAt, ControlPlaneAudit audit);

    /** Persists a vector-visibility reconciliation record in the same transaction as the chunk state. */
    boolean setChunkEnabledAndEnqueueVectorSync(
            String chunkId,
            boolean enabled,
            Map<String, Object> cleanup,
            Instant updatedAt,
            ControlPlaneAudit audit
    );

    Optional<Map<String, Object>> markDocumentDeleted(String documentId, Instant deletedAt, ControlPlaneAudit audit);

    List<Map<String, Object>> listDeletedDocumentVersionsAwaitingCompensation(int limit);

    boolean markVersionObjectDeleted(String versionId, Instant deletedAt, ControlPlaneAudit audit);

    /** Persists cleanup work when an object was written but its metadata transaction failed. */
    void enqueueObjectCleanup(Map<String, Object> cleanup, ControlPlaneAudit audit);

    /** Makes a pre-persisted upload intent eligible for cleanup after a failed or unknown PUT. */
    void activateObjectCleanup(String cleanupId, Instant activatedAt);

    List<Map<String, Object>> listObjectCleanupCandidates(int limit);

    boolean completeObjectCleanup(String cleanupId, Instant completedAt, ControlPlaneAudit audit);

    void recordObjectCleanupFailure(String cleanupId, String errorCode, Instant failedAt);
}
