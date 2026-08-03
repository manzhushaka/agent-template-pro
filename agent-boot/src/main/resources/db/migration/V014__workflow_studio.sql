-- M7: 受控 Workflow Studio。DSL 快照随版本不可变；运行与节点事实逐节点落库，
-- 写节点通过 agent_task 确认门禁（confirmation_task_id），事件仅保存白名单字段与脱敏 payload。

CREATE TABLE IF NOT EXISTS agent_workflow (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    description_text VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    current_version_id VARCHAR(64) NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_agent_workflow_status(status, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_workflow_version (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    dsl_json JSON NOT NULL,
    resource_bindings_json JSON NOT NULL,
    description_text VARCHAR(1000) NULL,
    created_by VARCHAR(120) NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_workflow_version_no(workflow_id, version_no),
    INDEX idx_agent_workflow_version_status(workflow_id, status, version_no)
);

CREATE TABLE IF NOT EXISTS agent_workflow_run (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    workflow_version_id VARCHAR(64) NOT NULL,
    code VARCHAR(120) NOT NULL,
    dsl_json JSON NOT NULL,
    graph_thread_id VARCHAR(120) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    visitor_ref VARCHAR(80) NOT NULL,
    request_id VARCHAR(120) NOT NULL,
    variables_json JSON NOT NULL,
    visited_node_ids_json JSON NOT NULL,
    visited_edge_keys_json JSON NOT NULL,
    pending_edge_keys_json JSON NOT NULL,
    current_node_id VARCHAR(120) NULL,
    error_code VARCHAR(80) NULL,
    claim_owner VARCHAR(120) NULL,
    claim_lease_until DATETIME(6) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_agent_workflow_run_status(status, updated_at),
    INDEX idx_agent_workflow_run_visitor(visitor_ref, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_workflow_node_run (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(120) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json JSON NOT NULL,
    output_json JSON NOT NULL,
    confirmation_task_id VARCHAR(120) NULL,
    confirmation_version INT NOT NULL DEFAULT 0,
    confirmation_snapshot_hash VARCHAR(128) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(80) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_workflow_node_run(run_id, node_id),
    INDEX idx_agent_workflow_node_run_status(run_id, status)
);

CREATE TABLE IF NOT EXISTS agent_workflow_event (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    node_id VARCHAR(120) NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_workflow_event_seq(run_id, sequence),
    INDEX idx_agent_workflow_event_run(run_id, sequence)
);

INSERT INTO agent_admin_permission (code, display_name, created_at, updated_at) VALUES
    ('workflow:read', '查看工作流定义与运行', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('workflow:write', '编辑、发布与回滚工作流', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('workflow:run', '启动、暂停、恢复、停止与重试工作流运行', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE code = agent_admin_permission.code;

INSERT IGNORE INTO agent_admin_role_permission (role_code, permission_code, created_at)
SELECT 'ADMIN', code, UTC_TIMESTAMP() FROM agent_admin_permission
WHERE code LIKE 'workflow:%';

INSERT IGNORE INTO agent_admin_role_permission (role_code, permission_code, created_at) VALUES
    ('OPERATOR', 'workflow:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'workflow:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'workflow:run', UTC_TIMESTAMP()),
    ('VIEWER', 'workflow:read', UTC_TIMESTAMP());
