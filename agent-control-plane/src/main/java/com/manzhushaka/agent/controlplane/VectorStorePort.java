package com.manzhushaka.agent.controlplane;

import java.util.List;

/** Isolates knowledge retrieval from a concrete vector database implementation. */
public interface VectorStorePort {
    /**
     * Performs external embedding work before the repository acquires its short fencing
     * transaction. This method must not mutate the vector index.
     */
    List<PreparedVectorDocument> prepare(List<VectorDocument> documents);

    /**
     * Replaces vectors only from {@link KnowledgeRepository#completeIndexJobWithVectorMutation}.
     * Implementations must keep this mutation local and transaction-aware.
     */
    void replacePrepared(String knowledgeBaseId, String documentVersionId, List<PreparedVectorDocument> documents);

    void removeDocumentVersion(String knowledgeBaseId, String documentVersionId);

    /** Applies the durable chunk visibility decision to retrieval immediately. */
    void setChunkEnabled(String knowledgeBaseId, String chunkId, boolean enabled);

    List<VectorMatch> search(String knowledgeBaseId, String query, int topK, double threshold);

    record VectorDocument(String chunkId, String documentId, String documentVersionId, String content) {
    }

    /** Opaque adapter payload prepared outside the metadata fencing transaction. */
    record PreparedVectorDocument(VectorDocument document, List<Float> embedding) {
        public PreparedVectorDocument {
            embedding = embedding == null ? List.of() : List.copyOf(embedding);
        }
    }

    record VectorMatch(String chunkId, double score) {
    }
}
