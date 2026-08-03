CREATE TABLE IF NOT EXISTS agent_knowledge_embedding (
    chunk_id CHAR(36) PRIMARY KEY,
    knowledge_base_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    document_version_id CHAR(36) NOT NULL,
    dimensions INT NOT NULL,
    embedding_json JSON NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_knowledge_embedding_search(knowledge_base_id, enabled, updated_at),
    INDEX idx_knowledge_embedding_version(knowledge_base_id, document_version_id)
);
