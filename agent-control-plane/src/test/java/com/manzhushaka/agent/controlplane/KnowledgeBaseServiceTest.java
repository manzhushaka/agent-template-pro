package com.manzhushaka.agent.controlplane;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseServiceTest {
    @Test
    void indexesSourceAndReturnsOwnedCitationWithoutObjectReference() {
        Fixture fixture = fixture(new InMemoryObjectStorage());
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "support", "displayName", "Support"));
        Map<String, Object> document = fixture.service.uploadDocument(fixture.admin, id(base), "guide.md", "text/markdown", "退款需要提供订单号，客服会在三个工作日内处理。");

        assertFalse(document.containsKey("objectKey"));
        assertEquals("SUCCEEDED", fixture.service.processQueuedJobs("worker-a", 1).getFirst().get("status"));
        List<Map<String, Object>> results = fixture.service.retrieve(fixture.viewer, id(base), "退款订单号", 3, 0);

        assertEquals(1, results.size());
        Map<?, ?> citation = (Map<?, ?>) results.getFirst().get("citation");
        assertEquals(id(document), citation.get("documentId"));
        assertTrue(String.valueOf(citation.get("content")).contains("订单号"));
        assertFalse(results.getFirst().toString().contains("knowledge/"));

        String chunkId = String.valueOf(fixture.service.chunksPreview(fixture.admin, id(document)).getFirst().get("id"));
        fixture.service.setChunkEnabled(fixture.admin, id(document), chunkId, false);
        assertTrue(fixture.service.retrieve(fixture.viewer, id(base), "退款订单号", 3, 0).isEmpty());
    }

    @Test
    void enforcesRbacAndUploadConstraints() {
        Fixture fixture = fixture(new InMemoryObjectStorage());
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "policies", "displayName", "Policies"));

        assertThrows(ControlPlaneAccessDeniedException.class, () -> fixture.service.uploadDocument(fixture.viewer, id(base), "x.md", "text/markdown", "content"));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.uploadDocument(fixture.admin, id(base), "../x.md", "text/markdown", "content"));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.uploadDocument(fixture.admin, id(base), "x.png", "image/png", "content"));
    }

    @Test
    void recordsFailureThenAllowsExplicitRetry() {
        FlakyObjectStorage storage = new FlakyObjectStorage();
        Fixture fixture = fixture(storage);
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "retry", "displayName", "Retry"));
        fixture.service.uploadDocument(fixture.admin, id(base), "retry.txt", "text/plain", "hello knowledge retry");

        assertEquals("FAILED", fixture.service.processQueuedJobs("worker-a", 1).getFirst().get("status"));
        String jobId = id(fixture.service.indexJobs(fixture.admin, id(base)).getFirst());
        assertEquals("QUEUED", fixture.service.retryIndexJob(fixture.admin, jobId).get("status"));
        assertEquals("SUCCEEDED", fixture.service.processQueuedJobs("worker-b", 1).getFirst().get("status"));
    }

    @Test
    void onlyOneWorkerClaimsJobAndDeletionCompensatesSourceObject() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        Fixture fixture = fixture(storage);
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "concurrent", "displayName", "Concurrent"));
        Map<String, Object> document = fixture.service.uploadDocument(fixture.admin, id(base), "work.txt", "text/plain", "concurrent index work");
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<List<Map<String, Object>>>> runs = pool.invokeAll(List.<Callable<List<Map<String, Object>>>>of(
                    () -> fixture.service.processQueuedJobs("one", 1), () -> fixture.service.processQueuedJobs("two", 1)
            ));
            int completed = runs.stream().mapToInt(run -> {
                try { return run.get().size(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).sum();
            assertEquals(1, completed);
        }
        fixture.service.deleteDocument(fixture.admin, id(document));
        assertTrue(fixture.service.retrieve(fixture.viewer, id(base), "concurrent", 3, 0).isEmpty());
        assertEquals(1, fixture.service.compensateDeletedDocuments("cleanup", 10));
        assertThrows(IllegalStateException.class, () -> fixture.service.reindexDocument(fixture.admin, id(document)));
    }

    @Test
    void expiredLeaseTokenCannotCompleteAfterTheJobIsReclaimed() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        Fixture fixture = fixture(repository, new InMemoryObjectStorage());
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "fencing", "displayName", "Fencing"));
        fixture.service.uploadDocument(fixture.admin, id(base), "fence.txt", "text/plain", "lease fencing content");

        Instant claimedAt = Instant.now();
        Map<String, Object> first = repository.claimIndexJobs("worker", claimedAt, claimedAt.plusSeconds(1), 1).getFirst();
        Map<String, Object> second = repository.claimIndexJobs("worker", claimedAt.plusSeconds(2), claimedAt.plusSeconds(30), 1).getFirst();

        assertFalse(first.get("leaseOwner").equals(second.get("leaseOwner")));
        assertFalse(first.get("leaseToken").equals(second.get("leaseToken")));
        assertFalse(repository.completeIndexJob(id(first), String.valueOf(first.get("leaseOwner")), String.valueOf(first.get("leaseToken")), List.of(), claimedAt.plusSeconds(2), audit()));
        assertTrue(repository.completeIndexJob(id(second), String.valueOf(second.get("leaseOwner")), String.valueOf(second.get("leaseToken")), List.of(), claimedAt.plusSeconds(2), audit()));
        assertEquals("SUCCEEDED", repository.listIndexJobs(id(base)).getFirst().get("status"));
    }

    @Test
    void staleClaimCannotMutateVectorsAfterAReclaimHasCommitted() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        Fixture fixture = fixture(repository, new InMemoryObjectStorage(), vectors);
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "fence-vector", "displayName", "Fence vector"));
        Map<String, Object> document = fixture.service.uploadDocument(fixture.admin, id(base), "fence.txt", "text/plain", "generation fencing content");

        Instant claimedAt = Instant.now();
        Map<String, Object> first = repository.claimIndexJobs("worker-a", claimedAt, claimedAt.plusSeconds(1), 1).getFirst();
        Map<String, Object> second = repository.claimIndexJobs("worker-b", claimedAt.plusSeconds(2), claimedAt.plusSeconds(30), 1).getFirst();
        Map<String, Object> winner = chunk(id(base), id(document), String.valueOf(second.get("documentVersionId")), "winner-chunk", "generation winner content");
        List<VectorStorePort.PreparedVectorDocument> winnerVectors = vectors.prepare(List.of(new VectorStorePort.VectorDocument(
                "winner-chunk", id(document), String.valueOf(second.get("documentVersionId")), "generation winner content"
        )));

        assertTrue(repository.completeIndexJobWithVectorMutation(id(second), String.valueOf(second.get("leaseOwner")), String.valueOf(second.get("leaseToken")),
                List.of(winner), () -> vectors.replacePrepared(id(base), String.valueOf(second.get("documentVersionId")), winnerVectors), claimedAt.plusSeconds(2), audit()));
        AtomicBoolean staleMutationCalled = new AtomicBoolean();
        assertFalse(repository.completeIndexJobWithVectorMutation(id(first), String.valueOf(first.get("leaseOwner")), String.valueOf(first.get("leaseToken")),
                List.of(), () -> staleMutationCalled.set(true), claimedAt.plusSeconds(2), audit()));

        assertFalse(staleMutationCalled.get());
        assertEquals("winner-chunk", repository.listChunks(id(base), id(document), String.valueOf(second.get("documentVersionId"))).getFirst().get("id"));
        assertEquals("winner-chunk", vectors.search(id(base), "generation", 1, 0).getFirst().chunkId());
    }

    @Test
    void deletionFencesOldWorkerAndErasesChunkContent() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        Fixture fixture = fixture(repository, new InMemoryObjectStorage(), vectors);
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "delete-fence", "displayName", "Delete fence"));
        Map<String, Object> document = fixture.service.uploadDocument(fixture.admin, id(base), "delete.txt", "text/plain", "privacy deletion content");
        assertEquals("SUCCEEDED", fixture.service.processQueuedJobs("indexer", 1).getFirst().get("status"));
        String chunkId = String.valueOf(fixture.service.chunksPreview(fixture.admin, id(document)).getFirst().get("id"));
        fixture.service.reindexDocument(fixture.admin, id(document));
        Instant claimedAt = Instant.now();
        Map<String, Object> claim = repository.claimIndexJobs("worker", claimedAt, claimedAt.plusSeconds(30), 1).getFirst();

        fixture.service.deleteDocument(fixture.admin, id(document));
        assertTrue(repository.findChunk(chunkId).isEmpty());
        AtomicBoolean staleMutationCalled = new AtomicBoolean();
        assertFalse(repository.completeIndexJobWithVectorMutation(id(claim), String.valueOf(claim.get("leaseOwner")), String.valueOf(claim.get("leaseToken")),
                List.of(), () -> staleMutationCalled.set(true), claimedAt.plusSeconds(1), audit()));
        assertFalse(staleMutationCalled.get());
        assertTrue(vectors.search(id(base), "privacy", 3, 0).isEmpty());
    }

    @Test
    void doesNotPersistOrReturnKnowledgeBaseConfigAndDisabledBaseCannotRetrieveOrIndex() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        Fixture fixture = fixture(repository, new InMemoryObjectStorage());
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of(
                "code", "private-config", "displayName", "Private config", "config", Map.of("apiKey", "must-not-persist", "endpoint", "https://example.invalid")
        ));
        assertFalse(base.containsKey("config"));
        assertFalse(fixture.service.knowledgeBases(fixture.viewer).getFirst().containsKey("config"));
        Instant legacyTime = Instant.now();
        String legacyId = java.util.UUID.randomUUID().toString();
        repository.saveKnowledgeBase(Map.of("id", legacyId, "code", "legacy-config", "displayName", "Legacy config", "config", Map.of("token", "legacy-secret"),
                "status", "ACTIVE", "createdAt", legacyTime.toString(), "updatedAt", legacyTime.toString()), audit());
        assertFalse(fixture.service.knowledgeBases(fixture.viewer).stream().filter(item -> legacyId.equals(item.get("id"))).findFirst().orElseThrow().containsKey("config"));
        assertFalse(fixture.service.saveKnowledgeBase(fixture.admin, legacyId, Map.of("description", "sanitized on update")).containsKey("config"));
        Map<String, Object> document = fixture.service.uploadDocument(fixture.admin, id(base), "disabled.txt", "text/plain", "disabled base must not index");
        fixture.service.saveKnowledgeBase(fixture.admin, id(base), Map.of("status", "DISABLED"));

        assertTrue(fixture.service.processQueuedJobs("worker", 1).isEmpty());
        assertEquals("CANCELLED", fixture.service.indexJobs(fixture.admin, id(base)).getFirst().get("status"));
        assertThrows(IllegalStateException.class, () -> fixture.service.retrieve(fixture.viewer, id(base), "disabled", 3, 0));
        assertFalse(fixture.service.documents(fixture.viewer, id(base)).isEmpty());
        assertEquals(id(document), id(fixture.service.documents(fixture.viewer, id(base)).getFirst()));
    }

    @Test
    void persistsVisibilityReconciliationWhenVectorStoreIsTemporarilyUnavailable() {
        FlakyVisibilityVectorStore vectors = new FlakyVisibilityVectorStore();
        Fixture fixture = fixture(new InMemoryKnowledgeRepository(), new InMemoryObjectStorage(), vectors);
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "visibility-sync", "displayName", "Visibility sync"));
        Map<String, Object> document = fixture.service.uploadDocument(fixture.admin, id(base), "visibility.txt", "text/plain", "visibility synchronization content");
        assertEquals("SUCCEEDED", fixture.service.processQueuedJobs("worker", 1).getFirst().get("status"));
        String chunkId = String.valueOf(fixture.service.chunksPreview(fixture.admin, id(document)).getFirst().get("id"));

        fixture.service.setChunkEnabled(fixture.admin, id(document), chunkId, false);
        assertTrue(fixture.service.retrieve(fixture.viewer, id(base), "visibility", 3, 0).isEmpty());
        assertEquals(1, fixture.service.compensateDeletedDocuments("reconcile", 10));
        assertTrue(vectors.search(id(base), "visibility", 3, 0).isEmpty());
    }

    @Test
    void recordsCleanupIntentBeforeAnAmbiguousObjectPut() {
        UnknownPutObjectStorage storage = new UnknownPutObjectStorage();
        Fixture fixture = fixture(new InMemoryKnowledgeRepository(), storage);
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "unknown-put", "displayName", "Unknown put"));

        assertThrows(IllegalStateException.class, () -> fixture.service.uploadDocument(fixture.admin, id(base), "unknown.txt", "text/plain", "ambiguous upload content"));
        assertEquals(1, fixture.service.compensateDeletedDocuments("cleanup", 10));
        assertThrows(IllegalStateException.class, () -> storage.get(storage.lastObjectKey()));
    }

    @Test
    void recoversPreparedUploadIntentWhenTheProcessStopsBeforeItCanActivateCleanup() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        Fixture fixture = fixture(repository, storage);
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "prepared-intent", "displayName", "Prepared intent"));
        String objectKey = "knowledge/" + id(base) + "/orphan/version";
        storage.put(objectKey, "text/plain", "recoverable orphan".getBytes(StandardCharsets.UTF_8));
        Instant expired = Instant.now().minusSeconds(301);
        repository.enqueueObjectCleanup(Map.of("id", java.util.UUID.randomUUID().toString(), "objectKey", objectKey, "knowledgeBaseId", id(base),
                "documentVersionId", java.util.UUID.randomUUID().toString(), "reasonCode", "OBJECT_UPLOAD_INTENT", "status", "PREPARED", "attempts", 0,
                "createdAt", expired.toString(), "updatedAt", expired.toString()), audit());

        assertEquals(1, fixture.service.compensateDeletedDocuments("recovery", 10));
        assertThrows(IllegalStateException.class, () -> storage.get(objectKey));
    }

    @Test
    void rejectsTamperedObjectBeforeParsingOrIndexing() {
        Fixture fixture = fixture(new TamperingObjectStorage());
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "integrity", "displayName", "Integrity"));
        fixture.service.uploadDocument(fixture.admin, id(base), "integrity.txt", "text/plain", "expected source body");

        Map<String, Object> result = fixture.service.processQueuedJobs("worker", 1).getFirst();

        assertEquals("FAILED", result.get("status"));
        assertEquals("OBJECT_INTEGRITY_CHECK_FAILED", result.get("errorCode"));
        assertEquals("FAILED", fixture.service.indexJobs(fixture.admin, id(base)).getFirst().get("status"));
    }

    @Test
    void doesNotMarkIndexSuccessfulWhenEmbeddingWriteFails() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        Fixture fixture = fixture(repository, new InMemoryObjectStorage(), new FailingVectorStore());
        Map<String, Object> base = fixture.service.saveKnowledgeBase(fixture.admin, null, Map.of("code", "embedding-failure", "displayName", "Embedding failure"));
        fixture.service.uploadDocument(fixture.admin, id(base), "vector.txt", "text/plain", "embedding persistence must succeed first");

        assertEquals("FAILED", fixture.service.processQueuedJobs("worker", 1).getFirst().get("status"));
        assertTrue(repository.listChunks(id(base), null, null).isEmpty());
    }

    private Fixture fixture(ObjectStoragePort storage) {
        return fixture(new InMemoryKnowledgeRepository(), storage);
    }

    private Fixture fixture(InMemoryKnowledgeRepository repository, ObjectStoragePort storage) {
        return fixture(repository, storage, new InMemoryVectorStore());
    }

    private Fixture fixture(InMemoryKnowledgeRepository repository, ObjectStoragePort storage, VectorStorePort vectorStore) {
        KnowledgeBaseService service = new KnowledgeBaseService(repository, storage, vectorStore);
        return new Fixture(service, new ControlPlanePrincipal("admin", "ADMIN", Set.of(ControlPlaneService.KNOWLEDGE_READ, ControlPlaneService.KNOWLEDGE_WRITE)), new ControlPlanePrincipal("viewer", "VIEWER", Set.of(ControlPlaneService.KNOWLEDGE_READ)));
    }

    private String id(Map<String, Object> value) {
        return String.valueOf(value.get("id"));
    }

    private record Fixture(KnowledgeBaseService service, ControlPlanePrincipal admin, ControlPlanePrincipal viewer) {
    }

    private ControlPlaneAudit audit() {
        return new ControlPlaneAudit("audit-" + java.util.UUID.randomUUID(), "test", "TEST", "TEST", "TEST", Map.of(), Instant.now());
    }

    private Map<String, Object> chunk(String knowledgeBaseId, String documentId, String versionId, String chunkId, String content) {
        Instant now = Instant.now();
        return Map.of("id", chunkId, "knowledgeBaseId", knowledgeBaseId, "documentId", documentId, "documentVersionId", versionId,
                "chunkIndex", 0, "content", content, "enabled", true, "createdAt", now.toString(), "updatedAt", now.toString());
    }

    private static final class FlakyObjectStorage implements ObjectStoragePort {
        private final InMemoryObjectStorage delegate = new InMemoryObjectStorage();
        private boolean fail = true;
        @Override public StoredObject put(String key, String type, byte[] content) { return delegate.put(key, type, content); }
        @Override public byte[] get(String key) { if (fail) { fail = false; throw new IllegalStateException("OBJECT_UNAVAILABLE"); } return delegate.get(key); }
        @Override public void delete(String key) { delegate.delete(key); }
    }

    private static final class TamperingObjectStorage implements ObjectStoragePort {
        private final InMemoryObjectStorage delegate = new InMemoryObjectStorage();
        @Override public StoredObject put(String key, String type, byte[] content) { return delegate.put(key, type, content); }
        @Override public byte[] get(String key) { delegate.get(key); return "tampered object".getBytes(StandardCharsets.UTF_8); }
        @Override public void delete(String key) { delegate.delete(key); }
    }

    private static final class FailingVectorStore implements VectorStorePort {
        @Override public List<PreparedVectorDocument> prepare(List<VectorDocument> documents) { throw new IllegalStateException("EMBEDDING_UNAVAILABLE"); }
        @Override public void replacePrepared(String knowledgeBaseId, String documentVersionId, List<PreparedVectorDocument> documents) { }
        @Override public void removeDocumentVersion(String knowledgeBaseId, String documentVersionId) { }
        @Override public void setChunkEnabled(String knowledgeBaseId, String chunkId, boolean enabled) { }
        @Override public List<VectorMatch> search(String knowledgeBaseId, String query, int topK, double threshold) { return List.of(); }
    }

    private static final class FlakyVisibilityVectorStore implements VectorStorePort {
        private final InMemoryVectorStore delegate = new InMemoryVectorStore();
        private final AtomicBoolean failOnce = new AtomicBoolean(true);

        @Override public List<PreparedVectorDocument> prepare(List<VectorDocument> documents) { return delegate.prepare(documents); }
        @Override public void replacePrepared(String knowledgeBaseId, String documentVersionId, List<PreparedVectorDocument> documents) { delegate.replacePrepared(knowledgeBaseId, documentVersionId, documents); }
        @Override public void removeDocumentVersion(String knowledgeBaseId, String documentVersionId) { delegate.removeDocumentVersion(knowledgeBaseId, documentVersionId); }
        @Override public void setChunkEnabled(String knowledgeBaseId, String chunkId, boolean enabled) {
            if (failOnce.compareAndSet(true, false)) throw new IllegalStateException("VECTOR_UNAVAILABLE");
            delegate.setChunkEnabled(knowledgeBaseId, chunkId, enabled);
        }
        @Override public List<VectorMatch> search(String knowledgeBaseId, String query, int topK, double threshold) { return delegate.search(knowledgeBaseId, query, topK, threshold); }
    }

    private static final class UnknownPutObjectStorage implements ObjectStoragePort {
        private final InMemoryObjectStorage delegate = new InMemoryObjectStorage();
        private String objectKey;

        @Override public StoredObject put(String key, String type, byte[] content) {
            objectKey = key;
            delegate.put(key, type, content);
            throw new IllegalStateException("OBJECT_STORAGE_UNAVAILABLE");
        }
        @Override public byte[] get(String key) { return delegate.get(key); }
        @Override public void delete(String key) { delegate.delete(key); }
        String lastObjectKey() { return objectKey; }
    }
}
