CREATE TABLE IF NOT EXISTS agent_application (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    description_text VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    current_version_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_agent_application_status(status, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_application_version (
    id CHAR(36) PRIMARY KEY,
    application_id CHAR(36) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    model_code VARCHAR(120) NOT NULL,
    prompt_id CHAR(36) NOT NULL,
    prompt_version_id CHAR(36) NOT NULL,
    knowledge_base_id CHAR(36) NULL,
    config_json JSON NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_application_version_no(application_id, version_no),
    INDEX idx_agent_application_version_app(application_id, status, created_at)
);

CREATE TABLE IF NOT EXISTS agent_application_binding (
    id CHAR(36) PRIMARY KEY,
    version_id CHAR(36) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id VARCHAR(160) NOT NULL,
    resource_version VARCHAR(160) NULL,
    config_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_application_binding(version_id, resource_type, resource_id, resource_version),
    INDEX idx_agent_application_binding_version(version_id),
    INDEX idx_agent_application_binding_resource(resource_type, resource_id)
);

CREATE TABLE IF NOT EXISTS agent_application_publish_record (
    id CHAR(36) PRIMARY KEY,
    application_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    previous_version_id CHAR(36) NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_agent_application_publish_record_app(application_id, created_at)
);

CREATE TABLE IF NOT EXISTS agent_api_key (
    id CHAR(36) PRIMARY KEY,
    application_id CHAR(36) NOT NULL,
    key_hash CHAR(64) NOT NULL UNIQUE,
    key_prefix VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    scopes_json JSON NOT NULL,
    expires_at DATETIME(6) NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_agent_api_key_application(application_id, status, created_at)
);

INSERT INTO agent_admin_permission (code, display_name, created_at, updated_at) VALUES
    ('agent:read', '查看 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agent:write', '编辑 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agent:publish', '发布与回滚 Agent 版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agentapp:read', '查看 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agentapp:write', '编辑 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agentapp:publish', '发布与回滚 Agent 版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('apikey:read', '查看 API Key 状态', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('apikey:write', '创建、轮换与撤销 API Key', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE code = agent_admin_permission.code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at)
SELECT 'ADMIN', code, UTC_TIMESTAMP() FROM agent_admin_permission
WHERE code LIKE 'agent:%' OR code LIKE 'agentapp:%' OR code LIKE 'apikey:%'
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at) VALUES
    ('OPERATOR', 'agent:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'agent:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'agent:publish', UTC_TIMESTAMP()),
    ('OPERATOR', 'agentapp:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'agentapp:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'agentapp:publish', UTC_TIMESTAMP()),
    ('OPERATOR', 'apikey:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'apikey:write', UTC_TIMESTAMP()),
    ('VIEWER', 'agent:read', UTC_TIMESTAMP()),
    ('VIEWER', 'agentapp:read', UTC_TIMESTAMP()),
    ('VIEWER', 'apikey:read', UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;
