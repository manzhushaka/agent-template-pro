CREATE TABLE IF NOT EXISTS agent_admin_role (
    code VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_admin_permission (
    code VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_admin_role_permission (
    role_code VARCHAR(64) NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE IF NOT EXISTS agent_admin_session (
    id CHAR(36) PRIMARY KEY,
    token_hash CHAR(64) NOT NULL UNIQUE,
    admin_username VARCHAR(100) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_admin_session_active(token_hash, revoked_at, expires_at)
);

CREATE TABLE IF NOT EXISTS agent_admin_login_attempt (
    attempt_hash CHAR(64) PRIMARY KEY,
    failure_count INT NOT NULL,
    locked_until DATETIME(6) NULL,
    last_failed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_admin_login_attempt_expiry(locked_until, last_failed_at)
);

CREATE TABLE IF NOT EXISTS agent_secret_ref (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    ref_type VARCHAR(32) NOT NULL,
    reference_locator VARCHAR(500) NOT NULL,
    configured BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_control_plane_document (
    document_type VARCHAR(64) NOT NULL,
    id CHAR(36) NOT NULL,
    document_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (document_type, id),
    INDEX idx_control_document_updated(document_type, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_control_plane_audit (
    id CHAR(36) PRIMARY KEY,
    actor_username VARCHAR(100) NOT NULL,
    action_code VARCHAR(100) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id CHAR(36) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_control_audit_created(created_at),
    INDEX idx_control_audit_resource(resource_type, resource_id, created_at)
);

INSERT INTO agent_admin_role (code, display_name, created_at, updated_at) VALUES
    ('ADMIN', '管理员', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('OPERATOR', '运营人员', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('VIEWER', '只读人员', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE code = agent_admin_role.code;

INSERT INTO agent_admin_permission (code, display_name, created_at, updated_at) VALUES
    ('runtime:read', '查看运行数据', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('model:read', '查看模型', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('model:write', '编辑模型', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('model:test', '测试模型连接', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('prompt:read', '查看 Prompt', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('prompt:write', '编辑 Prompt', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('prompt:publish', '发布 Prompt', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('secret:read', '查看 SecretRef 状态', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('secret:write', '编辑 SecretRef', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('audit:read', '查看控制面审计', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE code = agent_admin_permission.code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at)
SELECT 'ADMIN', code, UTC_TIMESTAMP() FROM agent_admin_permission
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at) VALUES
    ('OPERATOR', 'runtime:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'model:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'model:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'model:test', UTC_TIMESTAMP()),
    ('OPERATOR', 'prompt:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'prompt:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'prompt:publish', UTC_TIMESTAMP()),
    ('OPERATOR', 'secret:read', UTC_TIMESTAMP()),
    ('VIEWER', 'runtime:read', UTC_TIMESTAMP()),
    ('VIEWER', 'model:read', UTC_TIMESTAMP()),
    ('VIEWER', 'prompt:read', UTC_TIMESTAMP()),
    ('VIEWER', 'secret:read', UTC_TIMESTAMP()),
    ('VIEWER', 'audit:read', UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;
