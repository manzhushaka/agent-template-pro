CREATE TABLE IF NOT EXISTS agent_knowledge_base (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    description_text VARCHAR(1000) NULL,
    config_json JSON NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_knowledge_base_status(status, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_knowledge_document (
    id CHAR(36) PRIMARY KEY,
    knowledge_base_id CHAR(36) NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    current_version_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_knowledge_document_base(knowledge_base_id, deleted_at, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_knowledge_document_version (
    id CHAR(36) PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    knowledge_base_id CHAR(36) NOT NULL,
    version_no INT NOT NULL,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    indexed_at DATETIME(6) NULL,
    object_deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_knowledge_document_version(document_id, version_no),
    INDEX idx_knowledge_version_compensation(document_id, object_deleted_at)
);

CREATE TABLE IF NOT EXISTS agent_knowledge_chunk (
    id CHAR(36) PRIMARY KEY,
    knowledge_base_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    document_version_id CHAR(36) NOT NULL,
    chunk_index INT NOT NULL,
    content_text MEDIUMTEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_knowledge_chunk_version_index(document_version_id, chunk_index),
    INDEX idx_knowledge_chunk_base_document(knowledge_base_id, document_id, enabled)
);

CREATE TABLE IF NOT EXISTS agent_knowledge_index_job (
    id CHAR(36) PRIMARY KEY,
    knowledge_base_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    document_version_id CHAR(36) NOT NULL,
    active_job_key CHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_knowledge_index_active(active_job_key),
    INDEX idx_knowledge_index_claim(status, next_attempt_at, lease_until, created_at),
    INDEX idx_knowledge_index_base(knowledge_base_id, created_at)
);

INSERT INTO agent_admin_permission (code, display_name, created_at, updated_at) VALUES
    ('knowledge:read', '查看知识库与检索结果', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('knowledge:write', '管理知识库与索引任务', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE code = agent_admin_permission.code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at)
SELECT 'ADMIN', code, UTC_TIMESTAMP() FROM agent_admin_permission WHERE code LIKE 'knowledge:%'
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at) VALUES
    ('OPERATOR', 'knowledge:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'knowledge:write', UTC_TIMESTAMP()),
    ('VIEWER', 'knowledge:read', UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;
