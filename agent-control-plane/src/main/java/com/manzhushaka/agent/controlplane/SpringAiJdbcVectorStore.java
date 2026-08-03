package com.manzhushaka.agent.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persistent vector adapter backed by a configured Spring AI EmbeddingModel. It is intentionally
 * selected only by the knowledge-embedding-jdbc profile; the deterministic in-memory adapter is
 * never presented as a production embedding implementation.
 */
public final class SpringAiJdbcVectorStore implements VectorStorePort {
    private static final int MAX_CANDIDATES = 10_000;
    private static final int MAX_DIMENSIONS = 8_192;
    private static final TypeReference<List<Float>> VECTOR_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EmbeddingModel embeddingModel;

    public SpringAiJdbcVectorStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            EmbeddingModel embeddingModel
    ) {
        this.jdbc = jdbc;
        this.json = json;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Retained for binary-compatible application wiring. Vector writes join the repository's
     * transaction, so transaction ownership deliberately lives in JdbcKnowledgeRepository.
     */
    public SpringAiJdbcVectorStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            EmbeddingModel embeddingModel,
            PlatformTransactionManager ignored
    ) {
        this(jdbc, json, embeddingModel);
    }

    @Override
    public List<PreparedVectorDocument> prepare(List<VectorDocument> documents) {
        List<VectorDocument> values = List.copyOf(documents);
        List<float[]> embeddings = embed(values.stream().map(VectorDocument::content).toList());
        if (embeddings.size() != values.size()) {
            throw new IllegalStateException("EMBEDDING_RESULT_INVALID");
        }
        List<PreparedVectorDocument> prepared = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            float[] embedding = valid(embeddings.get(index));
            List<Float> vector = new ArrayList<>(embedding.length);
            for (float value : embedding) {
                vector.add(value);
            }
            prepared.add(new PreparedVectorDocument(values.get(index), vector));
        }
        return List.copyOf(prepared);
    }

    @Override
    public void replacePrepared(String knowledgeBaseId, String documentVersionId, List<PreparedVectorDocument> documents) {
        List<PreparedVectorDocument> values = List.copyOf(documents);
        Instant now = Instant.now();
        jdbc.update("DELETE FROM agent_knowledge_embedding WHERE knowledge_base_id=? AND document_version_id=?", knowledgeBaseId, documentVersionId);
        for (PreparedVectorDocument prepared : values) {
            VectorDocument document = prepared.document();
            List<Float> embedding = prepared.embedding();
            validatePrepared(document, documentVersionId, embedding);
            jdbc.update("INSERT INTO agent_knowledge_embedding(chunk_id,knowledge_base_id,document_id,document_version_id,dimensions,embedding_json,enabled,created_at,updated_at) VALUES(?,?,?,?,?,CAST(? AS JSON),TRUE,?,?)",
                    document.chunkId(), knowledgeBaseId, document.documentId(), documentVersionId, embedding.size(),
                    write(embedding), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        }
    }

    @Override
    public void removeDocumentVersion(String knowledgeBaseId, String documentVersionId) {
        jdbc.update("DELETE FROM agent_knowledge_embedding WHERE knowledge_base_id=? AND document_version_id=?", knowledgeBaseId, documentVersionId);
    }

    @Override
    public void setChunkEnabled(String knowledgeBaseId, String chunkId, boolean enabled) {
        jdbc.update("UPDATE agent_knowledge_embedding SET enabled=?,updated_at=? WHERE knowledge_base_id=? AND chunk_id=?",
                enabled, java.sql.Timestamp.from(Instant.now()), knowledgeBaseId, chunkId);
    }

    @Override
    public List<VectorMatch> search(String knowledgeBaseId, String query, int topK, double threshold) {
        float[] queryVector = valid(embed(List.of(query)).getFirst());
        return jdbc.query("SELECT chunk_id,dimensions,embedding_json FROM agent_knowledge_embedding WHERE knowledge_base_id=? AND enabled=TRUE ORDER BY updated_at DESC LIMIT ?",
                        (resultSet, ignored) -> new StoredVector(resultSet.getString("chunk_id"), resultSet.getInt("dimensions"), read(resultSet.getString("embedding_json"))),
                        knowledgeBaseId, MAX_CANDIDATES)
                .stream()
                .filter(vector -> vector.dimensions() == queryVector.length && vector.values().size() == queryVector.length)
                .map(vector -> new VectorMatch(vector.chunkId(), cosine(queryVector, vector.values())))
                .filter(match -> match.score() >= threshold)
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::chunkId))
                .limit(Math.clamp(topK, 1, 50))
                .toList();
    }

    private List<float[]> embed(List<String> values) {
        try {
            return embeddingModel.embed(values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("EMBEDDING_UNAVAILABLE", exception);
        }
    }

    private float[] valid(float[] value) {
        if (value == null || value.length == 0 || value.length > MAX_DIMENSIONS) {
            throw new IllegalStateException("EMBEDDING_RESULT_INVALID");
        }
        for (float item : value) {
            if (!Float.isFinite(item)) {
                throw new IllegalStateException("EMBEDDING_RESULT_INVALID");
            }
        }
        return value;
    }

    private double cosine(float[] left, List<Float> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            double item = right.get(index);
            dot += left[index] * item;
            leftNorm += left[index] * left[index];
            rightNorm += item * item;
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private void validatePrepared(VectorDocument document, String documentVersionId, List<Float> value) {
        if (document == null || !documentVersionId.equals(document.documentVersionId()) || value.isEmpty() || value.size() > MAX_DIMENSIONS) {
            throw new IllegalStateException("EMBEDDING_RESULT_INVALID");
        }
        for (Float item : value) {
            if (item == null || !Float.isFinite(item)) {
                throw new IllegalStateException("EMBEDDING_RESULT_INVALID");
            }
        }
    }

    private String write(List<Float> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("EMBEDDING_SERIALIZATION_FAILED", exception);
        }
    }

    private List<Float> read(String value) {
        try {
            return json.readValue(value, VECTOR_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("EMBEDDING_STORAGE_INVALID", exception);
        }
    }

    private record StoredVector(String chunkId, int dimensions, List<Float> values) {
    }
}
