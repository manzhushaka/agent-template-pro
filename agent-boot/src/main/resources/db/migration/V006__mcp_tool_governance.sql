CREATE TABLE IF NOT EXISTS agent_mcp_server (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    transport VARCHAR(32) NOT NULL,
    endpoint VARCHAR(500) NULL,
    command_ref VARCHAR(200) NULL,
    secret_ref_id CHAR(36) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_status VARCHAR(32) NULL,
    last_tested_at DATETIME(6) NULL,
    last_synced_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_mcp_server_status(enabled, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_mcp_tool (
    id CHAR(36) PRIMARY KEY,
    mcp_server_id CHAR(36) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    write_tool BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    latest_version_id CHAR(36) NULL,
    retired_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_mcp_tool_server_name(mcp_server_id, tool_name),
    INDEX idx_mcp_tool_server_enabled(mcp_server_id, enabled, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_mcp_tool_version (
    id CHAR(36) PRIMARY KEY,
    mcp_tool_id CHAR(36) NOT NULL,
    mcp_server_id CHAR(36) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    schema_digest CHAR(64) NOT NULL,
    input_schema_json JSON NOT NULL,
    output_schema_json JSON NOT NULL,
    description_text TEXT NULL,
    risk_level VARCHAR(16) NOT NULL,
    write_tool BOOLEAN NOT NULL DEFAULT FALSE,
    discovered_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_mcp_tool_version_schema(mcp_server_id, tool_name, schema_digest),
    INDEX idx_mcp_tool_version_tool(mcp_tool_id, created_at)
);

CREATE TABLE IF NOT EXISTS agent_mcp_agent_binding (
    id CHAR(36) PRIMARY KEY,
    agent_code VARCHAR(120) NOT NULL,
    mcp_tool_id CHAR(36) NOT NULL,
    mcp_tool_version_id CHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_mcp_tool_version(agent_code, mcp_tool_version_id),
    INDEX idx_mcp_binding_tool(mcp_tool_id, enabled)
);

CREATE TABLE IF NOT EXISTS agent_mcp_sync_run (
    id CHAR(36) PRIMARY KEY,
    mcp_server_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    discovered_tool_count INT NOT NULL DEFAULT 0,
    created_version_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(100) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    INDEX idx_mcp_sync_server(mcp_server_id, started_at)
);

INSERT INTO agent_admin_permission (code, display_name, created_at, updated_at) VALUES
    ('mcp:read', '查看 MCP 与工具目录', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:write', '编辑 MCP 与工具状态', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:test', '测试 MCP 连接与工具 Debug', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:sync', '同步 MCP Tool Schema', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:bind', '绑定 Agent 与 MCP Tool 版本', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE code = agent_admin_permission.code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at)
SELECT 'ADMIN', code, UTC_TIMESTAMP() FROM agent_admin_permission WHERE code LIKE 'mcp:%'
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;

INSERT INTO agent_admin_role_permission (role_code, permission_code, created_at) VALUES
    ('OPERATOR', 'mcp:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:test', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:sync', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:bind', UTC_TIMESTAMP()),
    ('VIEWER', 'mcp:read', UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE role_code = agent_admin_role_permission.role_code;
