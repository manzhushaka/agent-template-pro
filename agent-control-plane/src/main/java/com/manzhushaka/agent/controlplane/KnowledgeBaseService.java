package com.manzhushaka.agent.controlplane;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Knowledge source lifecycle. Source bodies remain in ObjectStoragePort; only chunk citations are
 * returned to callers. Index execution is lease-owned so workers can resume after process loss.
 */
public final class KnowledgeBaseService {
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int CHUNK_LENGTH = 800;
    private static final Duration INDEX_LEASE = Duration.ofMinutes(5);

    private final KnowledgeRepository repository;
    private final ObjectStoragePort objectStorage;
    private final VectorStorePort vectorStore;
    private final DocumentTextParser documentTextParser;
    private final Object[] documentLocks = new Object[257];

    public KnowledgeBaseService(KnowledgeRepository repository, ObjectStoragePort objectStorage, VectorStorePort vectorStore) {
        this(repository, objectStorage, vectorStore, new BoundedDocumentTextParser());
    }

    public KnowledgeBaseService(
            KnowledgeRepository repository,
            ObjectStoragePort objectStorage,
            VectorStorePort vectorStore,
            DocumentTextParser documentTextParser
    ) {
        this.repository = repository;
        this.objectStorage = objectStorage;
        this.vectorStore = vectorStore;
        this.documentTextParser = documentTextParser;
        for (int index = 0; index < documentLocks.length; index++) documentLocks[index] = new Object();
    }

    /** Compatibility constructor for isolated callers; production must inject a dedicated repository. */
    public KnowledgeBaseService(ControlPlaneRepository ignored, VectorStorePort vectorStore) {
        this(new InMemoryKnowledgeRepository(), new InMemoryObjectStorage(), vectorStore);
    }

    public List<Map<String, Object>> knowledgeBases(ControlPlanePrincipal principal) {
        require(principal, ControlPlaneService.KNOWLEDGE_READ);
        return repository.listKnowledgeBases().stream().map(this::safeBase).sorted(byUpdated()).toList();
    }

    public Map<String, Object> saveKnowledgeBase(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        require(principal, ControlPlaneService.KNOWLEDGE_WRITE);
        Map<String, Object> value = id == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base(id));
        copy(input, value, "code", "displayName", "description", "status");
        // Knowledge-base configuration currently has no secret-safe runtime contract. Do not retain
        // arbitrary provider credentials or legacy fields until a dedicated SecretRef exists.
        value.remove("config");
        value.put("id", id == null ? UUID.randomUUID().toString() : id);
        requireText(value, "code"); requireText(value, "displayName");
        value.put("code", String.valueOf(value.get("code")).trim());
        value.put("displayName", String.valueOf(value.get("displayName")).trim());
        value.put("status", value.getOrDefault("status", "ACTIVE"));
        if (!Set.of("ACTIVE", "DISABLED").contains(value.get("status"))) throw new IllegalArgumentException("无效的知识库状态。");
        Instant now = Instant.now(); value.putIfAbsent("createdAt", now.toString()); value.put("updatedAt", now.toString());
        repository.saveKnowledgeBase(immutable(value), audit(principal, "KNOWLEDGE_BASE_SAVED", "KNOWLEDGE_BASE", id(value), Map.of("code", value.get("code"))));
        return safeBase(value);
    }

    public List<Map<String, Object>> documents(ControlPlanePrincipal principal, String knowledgeBaseId) {
        require(principal, ControlPlaneService.KNOWLEDGE_READ); base(knowledgeBaseId);
        return repository.listDocuments(knowledgeBaseId, false).stream().map(this::safeDocument).toList();
    }

    public Map<String, Object> uploadDocument(ControlPlanePrincipal principal, String knowledgeBaseId, String name, String contentType, String content) {
        return uploadDocument(principal, knowledgeBaseId, name, contentType, content == null ? null : content.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> uploadDocument(ControlPlanePrincipal principal, String knowledgeBaseId, String name, String contentType, byte[] content) {
        require(principal, ControlPlaneService.KNOWLEDGE_WRITE);
        Map<String, Object> knowledgeBase = base(knowledgeBaseId);
        if (!"ACTIVE".equals(knowledgeBase.get("status"))) throw new IllegalStateException("已停用的知识库不能上传文档。");
        String normalizedType = normalizeType(contentType);
        validateName(name); validateContent(content);
        String documentId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String objectKey = "knowledge/" + knowledgeBaseId + "/" + documentId + "/" + versionId;
        Instant now = Instant.now();
        ObjectWriteIntent intent = prepareObjectWrite(principal, knowledgeBaseId, versionId, objectKey, normalizedType, content);
        ObjectStoragePort.StoredObject stored = putObject(intent, content);
        Map<String, Object> document = map(
                "id", documentId, "knowledgeBaseId", knowledgeBaseId, "name", name.trim(), "contentType", normalizedType,
                "currentVersionId", versionId, "status", "QUEUED", "createdAt", now.toString(), "updatedAt", now.toString());
        Map<String, Object> version = map(
                "id", versionId, "documentId", documentId, "knowledgeBaseId", knowledgeBaseId, "version", 1,
                "objectKey", stored.objectKey(), "contentType", stored.contentType(), "size", stored.size(), "sha256", stored.sha256(),
                "status", "QUEUED", "createdAt", now.toString(), "updatedAt", now.toString());
        Map<String, Object> job = map(
                "id", UUID.randomUUID().toString(), "knowledgeBaseId", knowledgeBaseId, "documentId", documentId,
                "documentVersionId", versionId, "status", "QUEUED", "attempts", 0, "nextAttemptAt", now.toString(),
                "createdAt", now.toString(), "updatedAt", now.toString());
        try {
            repository.createDocumentVersionAndJob(document, version, job, intent.cleanupId(), audit(principal, "KNOWLEDGE_DOCUMENT_UPLOADED", "KNOWLEDGE_DOCUMENT", documentId, Map.of("knowledgeBaseId", knowledgeBaseId, "size", stored.size())));
        } catch (RuntimeException exception) {
            activateObjectCleanup(intent.cleanupId(), exception);
            throw exception;
        }
        return safeDocument(document);
    }

    /** Runs a bounded amount of durable work; safe to call from a scheduler or a recovery process. */
    public List<Map<String, Object>> processQueuedJobs(String workerId, int limit) {
        String owner = workerId == null || workerId.isBlank() ? "knowledge-worker" : workerId;
        Instant now = Instant.now();
        return repository.claimIndexJobs(owner, now, now.plus(INDEX_LEASE), Math.clamp(limit, 1, 50)).stream()
                .map(this::processClaimedJob).toList();
    }

    public Map<String, Object> processNextJob(ControlPlanePrincipal principal) {
        require(principal, ControlPlaneService.KNOWLEDGE_WRITE);
        return processQueuedJobs("manual-" + principal.username(), 1).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有待处理索引任务。"));
    }

    public Map<String, Object> reindexDocument(ControlPlanePrincipal principal, String documentId) {
        require(principal, ControlPlaneService.KNOWLEDGE_WRITE);
        synchronized (lockFor(documentId)) {
            Map<String, Object> document = document(documentId);
            if (document.get("deletedAt") != null) throw new IllegalStateException("文档已删除。");
            Map<String, Object> version = version(String.valueOf(document.get("currentVersionId")));
            // Reindex is modeled as a new immutable source version rather than mutating a published chunk set.
            byte[] source = verifiedObject(version);
            Instant now = Instant.now();
            String versionId = UUID.randomUUID().toString();
            String objectKey = "knowledge/" + document.get("knowledgeBaseId") + "/" + documentId + "/" + versionId;
            ObjectWriteIntent intent = prepareObjectWrite(principal, String.valueOf(document.get("knowledgeBaseId")), versionId, objectKey, String.valueOf(document.get("contentType")), source);
            ObjectStoragePort.StoredObject stored = putObject(intent, source);
            Map<String, Object> nextVersion = map("id", versionId, "documentId", documentId, "knowledgeBaseId", document.get("knowledgeBaseId"),
                    "version", ((Number) version.get("version")).intValue() + 1, "objectKey", stored.objectKey(), "contentType", stored.contentType(), "size", stored.size(), "sha256", stored.sha256(), "status", "QUEUED", "createdAt", now.toString(), "updatedAt", now.toString());
            Map<String, Object> job = map("id", UUID.randomUUID().toString(), "knowledgeBaseId", document.get("knowledgeBaseId"), "documentId", documentId, "documentVersionId", versionId, "status", "QUEUED", "attempts", 0, "nextAttemptAt", now.toString(), "createdAt", now.toString(), "updatedAt", now.toString());
            try {
                repository.createNextVersionAndJob(documentId, nextVersion, job, intent.cleanupId(), now, audit(principal, "KNOWLEDGE_DOCUMENT_REINDEXED", "KNOWLEDGE_DOCUMENT", documentId, Map.of("version", nextVersion.get("version"))));
            } catch (RuntimeException exception) {
                activateObjectCleanup(intent.cleanupId(), exception);
                throw exception;
            }
            return safeDocument(document(documentId));
        }
    }

    public List<Map<String, Object>> chunksPreview(ControlPlanePrincipal principal, String documentId) {
        require(principal, ControlPlaneService.KNOWLEDGE_READ);
        Map<String, Object> document = document(documentId);
        return repository.listChunks(String.valueOf(document.get("knowledgeBaseId")), documentId, String.valueOf(document.get("currentVersionId"))).stream().map(this::safeChunk).toList();
    }

    public Map<String, Object> setChunkEnabled(ControlPlanePrincipal principal, String documentId, String chunkId, boolean enabled) {
        require(principal, ControlPlaneService.KNOWLEDGE_WRITE);
        Map<String, Object> document = document(documentId);
        boolean belongs = repository.listChunks(String.valueOf(document.get("knowledgeBaseId")), documentId, null).stream().anyMatch(chunk -> chunkId.equals(chunk.get("id")));
        Instant now = Instant.now();
        String cleanupId = UUID.randomUUID().toString();
        Map<String, Object> cleanup = map("id", cleanupId, "objectKey", "vector-sync/" + chunkId + "/" + cleanupId,
                "knowledgeBaseId", document.get("knowledgeBaseId"), "documentVersionId", document.get("currentVersionId"),
                "reasonCode", "VECTOR_CHUNK_VISIBILITY_SYNC", "status", "PENDING", "attempts", 0,
                "createdAt", now.toString(), "updatedAt", now.toString());
        if (!belongs || !repository.setChunkEnabledAndEnqueueVectorSync(chunkId, enabled, cleanup, now,
                audit(principal, enabled ? "KNOWLEDGE_CHUNK_ENABLED" : "KNOWLEDGE_CHUNK_DISABLED", "KNOWLEDGE_CHUNK", chunkId, Map.of("documentId", documentId)))) {
            throw new IllegalArgumentException("Chunk 不存在。");
        }
        try {
            vectorStore.setChunkEnabled(String.valueOf(document.get("knowledgeBaseId")), chunkId, enabled);
            repository.completeObjectCleanup(cleanupId, Instant.now(), systemAudit(principal.username(), "KNOWLEDGE_VECTOR_VISIBILITY_SYNCED", "KNOWLEDGE_CHUNK", chunkId));
        } catch (RuntimeException exception) {
            // The reconciliation record was committed with the new chunk visibility decision.
        }
        return repository.listChunks(String.valueOf(document.get("knowledgeBaseId")), documentId, null).stream().filter(chunk -> chunkId.equals(chunk.get("id"))).findFirst().map(this::safeChunk).orElseThrow();
    }

    public List<Map<String, Object>> indexJobs(ControlPlanePrincipal principal, String knowledgeBaseId) {
        require(principal, ControlPlaneService.KNOWLEDGE_READ);
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) base(knowledgeBaseId);
        return repository.listIndexJobs(knowledgeBaseId).stream().map(KnowledgeBaseService::safeJob).toList();
    }

    public Map<String, Object> retryIndexJob(ControlPlanePrincipal principal, String jobId) {
        require(principal, ControlPlaneService.KNOWLEDGE_WRITE);
        return repository.retryIndexJob(jobId, Instant.now(), audit(principal, "KNOWLEDGE_INDEX_RETRIED", "KNOWLEDGE_INDEX_JOB", jobId, Map.of()))
                .map(KnowledgeBaseService::safeJob).orElseThrow(() -> new IllegalStateException("只有失败的索引任务可以重试。"));
    }

    public void deleteDocument(ControlPlanePrincipal principal, String documentId) {
        require(principal, ControlPlaneService.KNOWLEDGE_WRITE);
        synchronized (lockFor(documentId)) {
            Map<String, Object> document = document(documentId);
            List<Map<String, Object>> versions = repository.listDocumentVersions(documentId);
            Instant now = Instant.now();
            repository.markDocumentDeleted(documentId, now, audit(principal, "KNOWLEDGE_DOCUMENT_DELETE_REQUESTED", "KNOWLEDGE_DOCUMENT", documentId, Map.of("knowledgeBaseId", document.get("knowledgeBaseId"))))
                    .orElseThrow(() -> new IllegalStateException("文档已删除或不存在。"));
            for (Map<String, Object> version : versions) {
                try {
                    vectorStore.removeDocumentVersion(String.valueOf(document.get("knowledgeBaseId")), String.valueOf(version.get("id")));
                } catch (RuntimeException ignored) {
                    // The durable deleted-version compensation record retries the vector removal.
                }
            }
        }
    }

    /** Deletes source objects only after metadata deletion was committed; failures remain retryable. */
    public int compensateDeletedDocuments(String workerId, int limit) {
        int completed = 0;
        for (Map<String, Object> version : repository.listDeletedDocumentVersionsAwaitingCompensation(Math.clamp(limit, 1, 100))) {
            try {
                vectorStore.removeDocumentVersion(String.valueOf(version.get("knowledgeBaseId")), String.valueOf(version.get("id")));
                objectStorage.delete(String.valueOf(version.get("objectKey")));
                if (repository.markVersionObjectDeleted(String.valueOf(version.get("id")), Instant.now(), systemAudit(workerId, "KNOWLEDGE_OBJECT_COMPENSATED", "KNOWLEDGE_DOCUMENT_VERSION", String.valueOf(version.get("id"))))) completed++;
            } catch (RuntimeException ignored) {
                // The next scheduled pass retries the durable compensation candidate.
            }
        }
        for (Map<String, Object> cleanup : repository.listObjectCleanupCandidates(Math.clamp(limit, 1, 100))) {
            try {
                if ("VECTOR_CHUNK_VISIBILITY_SYNC".equals(cleanup.get("reasonCode"))) {
                    synchronizeChunkVisibility(cleanup);
                } else {
                    if (cleanup.get("documentVersionId") != null) vectorStore.removeDocumentVersion(String.valueOf(cleanup.get("knowledgeBaseId")), String.valueOf(cleanup.get("documentVersionId")));
                    objectStorage.delete(String.valueOf(cleanup.get("objectKey")));
                }
                if (repository.completeObjectCleanup(String.valueOf(cleanup.get("id")), Instant.now(), systemAudit(workerId, "KNOWLEDGE_ORPHAN_OBJECT_COMPENSATED", "KNOWLEDGE_OBJECT_CLEANUP", String.valueOf(cleanup.get("id"))))) completed++;
            } catch (RuntimeException exception) {
                repository.recordObjectCleanupFailure(String.valueOf(cleanup.get("id")), errorCode(exception), Instant.now());
            }
        }
        return completed;
    }

    public List<Map<String, Object>> retrieve(ControlPlanePrincipal principal, String knowledgeBaseId, String query, int topK, double threshold) {
        require(principal, ControlPlaneService.KNOWLEDGE_READ);
        return retrieveInternal(knowledgeBaseId, query, topK, threshold);
    }

    /**
     * Internal retrieval used by the open Agent API after API-key authentication. It reuses the
     * same chunk/vector read path and citation shape; no principal or console permission is
     * involved because the caller has already passed the API-key gate.
     */
    public List<Map<String, Object>> retrieveForRuntime(String knowledgeBaseId, String query, int topK, double threshold) {
        return retrieveInternal(knowledgeBaseId, query, topK, threshold);
    }

    private List<Map<String, Object>> retrieveInternal(String knowledgeBaseId, String query, int topK, double threshold) {
        Map<String, Object> knowledgeBase = base(knowledgeBaseId);
        if (!"ACTIVE".equals(knowledgeBase.get("status"))) throw new IllegalStateException("已停用的知识库不能检索。");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("检索问题不能为空。");
        if (Double.isNaN(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("检索阈值必须在 0 到 1 之间。");
        Map<String, Map<String, Object>> chunks = new LinkedHashMap<>();
        repository.listChunks(knowledgeBaseId, null, null).stream().filter(chunk -> Boolean.TRUE.equals(chunk.get("enabled"))).forEach(chunk -> chunks.put(String.valueOf(chunk.get("id")), chunk));
        return vectorStore.search(knowledgeBaseId, query, Math.clamp(topK, 1, 50), threshold).stream()
                .map(match -> citation(knowledgeBaseId, match, chunks.get(match.chunkId())))
                .filter(java.util.Objects::nonNull).toList();
    }

    private Map<String, Object> processClaimedJob(Map<String, Object> job) {
        String jobId = String.valueOf(job.get("id"));
        String leaseOwner = String.valueOf(job.get("leaseOwner"));
        String leaseToken = String.valueOf(job.get("leaseToken"));
        synchronized (lockFor(String.valueOf(job.get("documentId")))) {
            try {
                Map<String, Object> document = document(String.valueOf(job.get("documentId")));
                if (document.get("deletedAt") != null || !job.get("documentVersionId").equals(document.get("currentVersionId"))) return Map.of("id", jobId, "status", "CANCELLED");
                Map<String, Object> version = version(String.valueOf(job.get("documentVersionId")));
                DocumentTextParser.ParsedDocument parsed = documentTextParser.parse(String.valueOf(version.get("contentType")), verifiedObject(version));
                List<Map<String, Object>> chunks = chunks(job, parsed.text());
                List<VectorStorePort.PreparedVectorDocument> vectors = vectorStore.prepare(chunks.stream()
                        .filter(chunk -> Boolean.TRUE.equals(chunk.get("enabled")))
                        .map(chunk -> new VectorStorePort.VectorDocument(String.valueOf(chunk.get("id")), String.valueOf(chunk.get("documentId")), String.valueOf(chunk.get("documentVersionId")), String.valueOf(chunk.get("content"))))
                        .toList());
                boolean completed = repository.completeIndexJobWithVectorMutation(jobId, leaseOwner, leaseToken, chunks,
                        () -> vectorStore.replacePrepared(String.valueOf(job.get("knowledgeBaseId")), String.valueOf(job.get("documentVersionId")), vectors),
                        Instant.now(), systemAudit(leaseOwner, "KNOWLEDGE_INDEX_SUCCEEDED", "KNOWLEDGE_INDEX_JOB", jobId));
                if (!completed) return Map.of("id", jobId, "status", "LEASE_LOST");
                return repository.listIndexJobs(String.valueOf(job.get("knowledgeBaseId"))).stream().filter(item -> jobId.equals(item.get("id"))).findFirst().map(KnowledgeBaseService::safeJob).orElseThrow();
            } catch (RuntimeException exception) {
                boolean failed = repository.failIndexJob(jobId, leaseOwner, leaseToken, errorCode(exception), Instant.now(), Instant.now(), systemAudit(leaseOwner, "KNOWLEDGE_INDEX_FAILED", "KNOWLEDGE_INDEX_JOB", jobId));
                return failed ? Map.of("id", jobId, "status", "FAILED", "errorCode", errorCode(exception)) : Map.of("id", jobId, "status", "LEASE_LOST");
            }
        }
    }

    private List<Map<String, Object>> chunks(Map<String, Object> job, String content) {
        if (content.isBlank()) throw new IllegalArgumentException("文档没有可索引内容。");
        List<Map<String, Object>> result = new ArrayList<>();
        Instant now = Instant.now();
        for (int offset = 0, index = 0; offset < content.length(); offset += CHUNK_LENGTH, index++) {
            int end = Math.min(content.length(), offset + CHUNK_LENGTH);
            if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) end--;
            String text = content.substring(offset, end).trim();
            if (!text.isBlank()) result.add(map("id", UUID.randomUUID().toString(), "knowledgeBaseId", job.get("knowledgeBaseId"), "documentId", job.get("documentId"), "documentVersionId", job.get("documentVersionId"), "chunkIndex", index, "content", text, "enabled", true, "createdAt", now.toString(), "updatedAt", now.toString()));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("文档没有可索引内容。");
        return result;
    }

    private Map<String, Object> citation(String knowledgeBaseId, VectorStorePort.VectorMatch match, Map<String, Object> chunk) {
        if (chunk == null) return null;
        return Map.of("score", match.score(), "rerankScore", match.score(), "citation", Map.of("knowledgeBaseId", knowledgeBaseId,
                "documentId", chunk.get("documentId"), "documentVersionId", chunk.get("documentVersionId"), "chunkId", chunk.get("id"), "chunkIndex", chunk.get("chunkIndex"), "content", chunk.get("content")));
    }
    private byte[] verifiedObject(Map<String, Object> version) {
        long expectedSize = ((Number) version.get("size")).longValue();
        if (expectedSize < 0 || expectedSize > MAX_DOCUMENT_BYTES) throw new IllegalStateException("OBJECT_INTEGRITY_CHECK_FAILED");
        byte[] content = objectStorage.get(String.valueOf(version.get("objectKey")), expectedSize);
        String expectedSha256 = String.valueOf(version.get("sha256"));
        if (content.length != expectedSize || content.length > MAX_DOCUMENT_BYTES || !expectedSha256.equals(sha256(content))) {
            throw new IllegalStateException("OBJECT_INTEGRITY_CHECK_FAILED");
        }
        return content;
    }

    private ObjectWriteIntent prepareObjectWrite(
            ControlPlanePrincipal principal,
            String knowledgeBaseId,
            String versionId,
            String objectKey,
            String contentType,
            byte[] content
    ) {
        Instant now = Instant.now();
        String cleanupId = UUID.randomUUID().toString();
        ObjectStoragePort.StoredObject expected = new ObjectStoragePort.StoredObject(objectKey, contentType, content.length, sha256(content));
        repository.enqueueObjectCleanup(map("id", cleanupId, "objectKey", objectKey, "knowledgeBaseId", knowledgeBaseId,
                "documentVersionId", versionId, "reasonCode", "OBJECT_UPLOAD_INTENT", "status", "PREPARED", "attempts", 0,
                "createdAt", now.toString(), "updatedAt", now.toString()),
                audit(principal, "KNOWLEDGE_OBJECT_UPLOAD_INTENT_PERSISTED", "KNOWLEDGE_DOCUMENT_VERSION", versionId, Map.of("size", content.length)));
        return new ObjectWriteIntent(cleanupId, expected);
    }

    private ObjectStoragePort.StoredObject putObject(ObjectWriteIntent intent, byte[] content) {
        try {
            ObjectStoragePort.StoredObject stored = objectStorage.put(intent.expected().objectKey(), intent.expected().contentType(), content);
            if (!intent.expected().equals(stored)) throw new IllegalStateException("OBJECT_STORAGE_INTEGRITY_CHECK_FAILED");
            return stored;
        } catch (RuntimeException exception) {
            activateObjectCleanup(intent.cleanupId(), exception);
            throw exception;
        }
    }

    private void activateObjectCleanup(String cleanupId, RuntimeException original) {
        try {
            repository.activateObjectCleanup(cleanupId, Instant.now());
        } catch (RuntimeException activationFailure) {
            // A pre-persisted intent is also swept after its bounded PREPARED grace interval.
            original.addSuppressed(activationFailure);
        }
    }

    private void synchronizeChunkVisibility(Map<String, Object> cleanup) {
        String objectKey = String.valueOf(cleanup.get("objectKey"));
        String prefix = "vector-sync/";
        if (!objectKey.startsWith(prefix)) throw new IllegalStateException("KNOWLEDGE_VECTOR_SYNC_INVALID");
        int separator = objectKey.indexOf('/', prefix.length());
        if (separator < 0) throw new IllegalStateException("KNOWLEDGE_VECTOR_SYNC_INVALID");
        String chunkId = objectKey.substring(prefix.length(), separator);
        repository.findChunk(chunkId).ifPresent(chunk -> vectorStore.setChunkEnabled(String.valueOf(cleanup.get("knowledgeBaseId")), chunkId, Boolean.TRUE.equals(chunk.get("enabled"))));
    }

    private Object lockFor(String documentId) {
        return documentLocks[Math.floorMod(documentId.hashCode(), documentLocks.length)];
    }

    private String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("OBJECT_INTEGRITY_CHECK_FAILED", exception);
        }
    }
    private Map<String, Object> base(String id) { return repository.findKnowledgeBase(id).orElseThrow(() -> new IllegalArgumentException("知识库不存在。")); }
    private Map<String, Object> document(String id) { return repository.findDocument(id).orElseThrow(() -> new IllegalArgumentException("文档不存在。")); }
    private Map<String, Object> version(String id) { return repository.findDocumentVersion(id).orElseThrow(() -> new IllegalArgumentException("文档版本不存在。")); }
    private void require(ControlPlanePrincipal principal, String permission) { if (principal == null || !principal.permissions().contains(permission)) throw new ControlPlaneAccessDeniedException(); }
    private void validateName(String name) { if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH || name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("无效的文档名称。"); }
    private void validateContent(byte[] content) { if (content == null || content.length == 0 || content.length > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("文档为空或超过大小限制。"); }
    private String normalizeType(String contentType) { String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim(); if (!ALLOWED_TYPES.contains(normalized)) throw new IllegalArgumentException("仅支持 TXT、Markdown、PDF 或 DOCX 文档。"); return normalized; }
    private void requireText(Map<String, Object> value, String field) { if (!(value.get(field) instanceof String text) || text.isBlank()) throw new IllegalArgumentException(field + " 不能为空。"); }
    private void copy(Map<String, Object> source, Map<String, Object> target, String... keys) { if (source == null) return; for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key)); }
    private Comparator<Map<String, Object>> byUpdated() { return Comparator.comparing(value -> String.valueOf(value.get("updatedAt")), Comparator.reverseOrder()); }
    private Map<String, Object> safeBase(Map<String, Object> value) { Map<String, Object> safe = new LinkedHashMap<>(value); safe.remove("config"); return immutable(safe); }
    private Map<String, Object> safeDocument(Map<String, Object> value) { Map<String, Object> safe = new LinkedHashMap<>(value); safe.remove("objectKey"); return immutable(safe); }
    private Map<String, Object> safeChunk(Map<String, Object> value) { return immutable(value); }
    private static Map<String, Object> safeJob(Map<String, Object> value) { Map<String, Object> safe = new LinkedHashMap<>(value); safe.remove("leaseOwner"); safe.remove("leaseToken"); safe.remove("leaseEpoch"); return immutable(safe); }
    private ControlPlaneAudit audit(ControlPlanePrincipal principal, String action, String type, String id, Map<String, Object> metadata) { return new ControlPlaneAudit(UUID.randomUUID().toString(), principal.username(), action, type, id, metadata, Instant.now()); }
    private ControlPlaneAudit systemAudit(String worker, String action, String type, String id) { return new ControlPlaneAudit(UUID.randomUUID().toString(), worker == null || worker.isBlank() ? "knowledge-worker" : worker, action, type, id, Map.of(), Instant.now()); }
    private String errorCode(RuntimeException exception) { String message = exception.getMessage(); if (message != null && message.matches("[A-Z_]{3,80}")) return message; return "KNOWLEDGE_INDEX_FAILED"; }
    private static String id(Map<String, Object> value) { return String.valueOf(value.get("id")); }
    private record ObjectWriteIntent(String cleanupId, ObjectStoragePort.StoredObject expected) { }
    private static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int index = 0; index < values.length; index += 2) if (values[index + 1] != null) result.put(String.valueOf(values[index]), values[index + 1]); return Map.copyOf(result); }
    private static Map<String, Object> immutable(Map<String, Object> value) { return Map.copyOf(value); }
}
