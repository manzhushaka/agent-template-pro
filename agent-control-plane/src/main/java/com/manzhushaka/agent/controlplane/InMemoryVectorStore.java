package com.manzhushaka.agent.controlplane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic development vector store; production adapters implement {@link VectorStorePort}. */
public final class InMemoryVectorStore implements VectorStorePort {
    private final Map<String, Map<String, IndexedDocument>> documents = new ConcurrentHashMap<>();

    @Override
    public List<PreparedVectorDocument> prepare(List<VectorDocument> entries) {
        return List.copyOf(entries).stream().map(entry -> new PreparedVectorDocument(entry, List.of())).toList();
    }

    @Override
    public void replacePrepared(String knowledgeBaseId, String documentVersionId, List<PreparedVectorDocument> entries) {
        Map<String, IndexedDocument> bucket = documents.computeIfAbsent(knowledgeBaseId, ignored -> new ConcurrentHashMap<>());
        bucket.entrySet().removeIf(entry -> entry.getValue().document().documentVersionId().equals(documentVersionId));
        entries.forEach(entry -> bucket.put(entry.document().chunkId(), new IndexedDocument(entry.document(), true)));
    }

    @Override
    public void removeDocumentVersion(String knowledgeBaseId, String documentVersionId) {
        documents.getOrDefault(knowledgeBaseId, Map.of()).entrySet()
                .removeIf(entry -> entry.getValue().document().documentVersionId().equals(documentVersionId));
    }

    @Override
    public void setChunkEnabled(String knowledgeBaseId, String chunkId, boolean enabled) {
        documents.computeIfPresent(knowledgeBaseId, (ignored, bucket) -> {
            bucket.computeIfPresent(chunkId, (key, document) -> new IndexedDocument(document.document(), enabled));
            return bucket;
        });
    }

    @Override
    public List<VectorMatch> search(String knowledgeBaseId, String query, int topK, double threshold) {
        Map<String, Integer> terms = terms(query);
        if (terms.isEmpty()) return List.of();
        return documents.getOrDefault(knowledgeBaseId, Map.of()).values().stream()
                .filter(IndexedDocument::enabled)
                .map(document -> new VectorMatch(document.document().chunkId(), cosine(terms, terms(document.document().content()))))
                .filter(match -> match.score() >= threshold)
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::chunkId))
                .limit(Math.clamp(topK, 1, 50))
                .toList();
    }

    private Map<String, Integer> terms(String content) {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        for (String term : content.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() > 1) result.merge(term, 1, Integer::sum);
        }
        return result;
    }

    private double cosine(Map<String, Integer> left, Map<String, Integer> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (Map.Entry<String, Integer> entry : left.entrySet()) {
            leftNorm += entry.getValue() * entry.getValue();
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0);
        }
        for (int value : right.values()) rightNorm += value * value;
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private record IndexedDocument(VectorDocument document, boolean enabled) {
    }
}
