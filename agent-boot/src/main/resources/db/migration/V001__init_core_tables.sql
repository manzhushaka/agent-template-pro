CREATE TABLE IF NOT EXISTS agent_visitor (
    id VARCHAR(36) PRIMARY KEY,
    visitor_key_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    first_seen_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_conversation (
    id VARCHAR(64) PRIMARY KEY,
    visitor_id VARCHAR(64) NOT NULL,
    graph_thread_id VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_message_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_visitor_last_message(visitor_id, last_message_at)
);

CREATE TABLE IF NOT EXISTS agent_message (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content_ciphertext TEXT NULL,
    content_masked TEXT NULL,
    event_type VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_conversation_sequence(conversation_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS agent_task (
    id VARCHAR(64) PRIMARY KEY,
    visitor_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    domain_code VARCHAR(100) NOT NULL,
    action_code VARCHAR(150) NOT NULL,
    status VARCHAR(64) NOT NULL,
    external_ref VARCHAR(200) NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    confirmation_expires_at DATETIME(6) NULL,
    result_summary VARCHAR(1000) NULL,
    last_error_code VARCHAR(100) NULL,
    execution_lease_until DATETIME(6) NULL,
    next_recovery_at DATETIME(6) NULL,
    recovery_attempts INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_action_idempotency(action_code, idempotency_key),
    INDEX idx_status_updated(status, updated_at),
    INDEX idx_task_recovery(status, next_recovery_at, execution_lease_until)
);

CREATE TABLE IF NOT EXISTS agent_confirmation (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    confirmation_version INT NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NULL,
    decision VARCHAR(32) NULL,
    request_id VARCHAR(128) NULL,
    decided_at DATETIME(6) NULL,
    confirmed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_task_confirmation_version(task_id, confirmation_version)
);

CREATE TABLE IF NOT EXISTS agent_audit_event (
    id VARCHAR(64) PRIMARY KEY,
    visitor_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NULL,
    event_type VARCHAR(100) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    metadata_json JSON NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_task_created(task_id, created_at),
    INDEX idx_visitor_created(visitor_id, created_at)
);

CREATE TABLE IF NOT EXISTS agent_task_outbox (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(100) NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_outbox_dispatch(status, available_at, lease_until, created_at)
);

CREATE TABLE IF NOT EXISTS agent_graph_checkpoint (
    graph_thread_id VARCHAR(64) NOT NULL,
    checkpoint_id VARCHAR(64) NOT NULL,
    state_json JSON NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    next_node_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (graph_thread_id, checkpoint_id),
    INDEX idx_graph_checkpoint_latest(graph_thread_id, created_at)
);

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
    resource_id VARCHAR(64) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_control_audit_created(created_at),
    INDEX idx_control_audit_resource(resource_type, resource_id, created_at)
);

CREATE TABLE IF NOT EXISTS agent_mcp_server (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    transport VARCHAR(32) NOT NULL,
    endpoint VARCHAR(500) NULL,
    command_ref VARCHAR(200) NULL,
    stdio_arguments_json JSON NULL,
    secret_ref_id CHAR(36) NULL,
    timeout_ms INT NOT NULL DEFAULT 5000,
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
    active_sync_key CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL,
    discovered_tool_count INT NOT NULL DEFAULT 0,
    created_version_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(100) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    INDEX idx_mcp_sync_server(mcp_server_id, started_at),
    UNIQUE KEY uk_mcp_sync_active(active_sync_key)
);

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
    lease_token CHAR(36) NULL,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_knowledge_index_active(active_job_key),
    INDEX idx_knowledge_index_claim(status, next_attempt_at, lease_until, created_at),
    INDEX idx_knowledge_index_lease(lease_owner, lease_token, lease_until),
    INDEX idx_knowledge_index_base(knowledge_base_id, created_at)
);

CREATE TABLE IF NOT EXISTS agent_knowledge_object_cleanup (
    id CHAR(36) PRIMARY KEY,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    knowledge_base_id CHAR(36) NOT NULL,
    document_version_id CHAR(36) NULL,
    reason_code VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    INDEX idx_knowledge_object_cleanup_pending(status, created_at)
);

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

-- M6: 可观测性（Trace/Span）与评估中心（数据集、评估器、实验）。
-- 注意：span 表只允许写入白名单字段与脱敏后的 metadata，禁止持久化 Prompt/消息原文。

CREATE TABLE IF NOT EXISTS agent_runtime_span (
    id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(120) NOT NULL,
    parent_span_id VARCHAR(120) NULL,
    span_type VARCHAR(32) NOT NULL,
    span_name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    visitor_ref VARCHAR(80) NULL,
    conversation_id VARCHAR(120) NULL,
    task_id VARCHAR(120) NULL,
    request_id VARCHAR(120) NULL,
    agent_code VARCHAR(120) NULL,
    action_code VARCHAR(160) NULL,
    tool_code VARCHAR(160) NULL,
    model_provider VARCHAR(80) NULL,
    model_name VARCHAR(160) NULL,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(80) NULL,
    resource_versions_json JSON NULL,
    metadata_json JSON NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_agent_runtime_span_trace(trace_id, started_at),
    INDEX idx_agent_runtime_span_type_status(span_type, status, started_at),
    INDEX idx_agent_runtime_span_conversation(conversation_id, started_at),
    INDEX idx_agent_runtime_span_task(task_id),
    INDEX idx_agent_runtime_span_request(request_id)
);

CREATE TABLE IF NOT EXISTS agent_eval_dataset (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    description_text VARCHAR(1000) NULL,
    current_version_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_agent_eval_dataset_status(status, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_eval_dataset_version (
    id VARCHAR(64) PRIMARY KEY,
    dataset_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    description_text VARCHAR(1000) NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_eval_dataset_version_no(dataset_id, version_no),
    INDEX idx_agent_eval_dataset_version_dataset(dataset_id, status, created_at)
);

CREATE TABLE IF NOT EXISTS agent_eval_case (
    id VARCHAR(64) PRIMARY KEY,
    dataset_id VARCHAR(64) NOT NULL,
    dataset_version_id VARCHAR(64) NOT NULL,
    case_key VARCHAR(160) NOT NULL,
    category VARCHAR(80) NOT NULL,
    input_json JSON NOT NULL,
    expected_json JSON NOT NULL,
    tags_json JSON NOT NULL,
    source VARCHAR(32) NOT NULL,
    trace_id VARCHAR(120) NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_eval_case_key(dataset_version_id, case_key),
    INDEX idx_agent_eval_case_dataset(dataset_id, dataset_version_id, category)
);

CREATE TABLE IF NOT EXISTS agent_eval_evaluator (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    evaluator_type VARCHAR(32) NOT NULL,
    description_text VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    current_version_id VARCHAR(64) NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_eval_evaluator_version (
    id VARCHAR(64) PRIMARY KEY,
    evaluator_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    config_json JSON NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_eval_evaluator_version_no(evaluator_id, version_no)
);

CREATE TABLE IF NOT EXISTS agent_eval_experiment (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    dataset_id VARCHAR(64) NOT NULL,
    dataset_version_id VARCHAR(64) NOT NULL,
    agent_application_id VARCHAR(64) NULL,
    agent_version_id VARCHAR(64) NOT NULL,
    evaluator_version_ids_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    run_key VARCHAR(64) NOT NULL,
    total_cases INT NOT NULL,
    completed_cases INT NOT NULL,
    passed_cases INT NOT NULL,
    failed_cases INT NOT NULL,
    error_cases INT NOT NULL,
    cost_micros BIGINT NOT NULL DEFAULT 0,
    threshold_pass_rate DECIMAL(6,4) NULL,
    pass_rate DECIMAL(6,4) NULL,
    claim_owner VARCHAR(120) NULL,
    claim_lease_until DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_eval_experiment_run_key(agent_version_id, run_key),
    INDEX idx_agent_eval_experiment_status(status, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_eval_experiment_run (
    id VARCHAR(64) PRIMARY KEY,
    experiment_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    case_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    passed TINYINT(1) NULL,
    score DECIMAL(6,4) NULL,
    output_summary VARCHAR(4000) NULL,
    evaluator_results_json JSON NOT NULL,
    error_code VARCHAR(80) NULL,
    tokens_used INT NOT NULL DEFAULT 0,
    cost_micros BIGINT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_eval_experiment_run_case(experiment_id, case_id),
    INDEX idx_agent_eval_experiment_run_status(experiment_id, status)
);

INSERT IGNORE INTO agent_admin_role (code, display_name, created_at, updated_at) VALUES
    ('ADMIN', '管理员', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('OPERATOR', '运营人员', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('VIEWER', '只读人员', UTC_TIMESTAMP(), UTC_TIMESTAMP());

INSERT IGNORE INTO agent_admin_permission (code, display_name, created_at, updated_at) VALUES
    ('runtime:read', '查看运行数据', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('model:read', '查看模型', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('model:write', '编辑模型', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('model:test', '测试模型连接', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('prompt:read', '查看 Prompt', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('prompt:write', '编辑 Prompt', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('prompt:publish', '发布 Prompt', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('secret:read', '查看 SecretRef 状态', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('secret:write', '编辑 SecretRef', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('audit:read', '查看控制面审计', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:read', '查看 MCP 与工具目录', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:write', '编辑 MCP 与工具状态', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:test', '测试 MCP 连接与工具 Debug', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:sync', '同步 MCP Tool Schema', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('mcp:bind', '绑定 Agent 与 MCP Tool 版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('knowledge:read', '查看知识库与检索结果', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('knowledge:write', '管理知识库与索引任务', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agent:read', '查看 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agent:write', '编辑 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agent:publish', '发布与回滚 Agent 版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agentapp:read', '查看 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agentapp:write', '编辑 Agent 应用与版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('agentapp:publish', '发布与回滚 Agent 版本', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('apikey:read', '查看 API Key 状态', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('apikey:write', '创建、轮换与撤销 API Key', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('trace:read', '查看 Trace 与可观测性指标', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('eval:read', '查看数据集、评估器与实验', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('eval:write', '编辑数据集、评估器与实验', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('eval:run', '启动、停止与重试评估实验', UTC_TIMESTAMP(), UTC_TIMESTAMP());

INSERT IGNORE INTO agent_admin_role_permission (role_code, permission_code, created_at)
SELECT 'ADMIN', code, UTC_TIMESTAMP() FROM agent_admin_permission;

INSERT IGNORE INTO agent_admin_role_permission (role_code, permission_code, created_at) VALUES
    ('OPERATOR', 'runtime:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'model:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'model:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'model:test', UTC_TIMESTAMP()),
    ('OPERATOR', 'prompt:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'prompt:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'prompt:publish', UTC_TIMESTAMP()),
    ('OPERATOR', 'secret:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:test', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:sync', UTC_TIMESTAMP()),
    ('OPERATOR', 'mcp:bind', UTC_TIMESTAMP()),
    ('OPERATOR', 'knowledge:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'knowledge:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'agent:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'agent:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'agent:publish', UTC_TIMESTAMP()),
    ('OPERATOR', 'agentapp:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'agentapp:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'agentapp:publish', UTC_TIMESTAMP()),
    ('OPERATOR', 'apikey:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'apikey:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'trace:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'eval:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'eval:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'eval:run', UTC_TIMESTAMP()),
    ('VIEWER', 'runtime:read', UTC_TIMESTAMP()),
    ('VIEWER', 'model:read', UTC_TIMESTAMP()),
    ('VIEWER', 'prompt:read', UTC_TIMESTAMP()),
    ('VIEWER', 'secret:read', UTC_TIMESTAMP()),
    ('VIEWER', 'audit:read', UTC_TIMESTAMP()),
    ('VIEWER', 'mcp:read', UTC_TIMESTAMP()),
    ('VIEWER', 'knowledge:read', UTC_TIMESTAMP()),
    ('VIEWER', 'agent:read', UTC_TIMESTAMP()),
    ('VIEWER', 'agentapp:read', UTC_TIMESTAMP()),
    ('VIEWER', 'apikey:read', UTC_TIMESTAMP()),
    ('VIEWER', 'trace:read', UTC_TIMESTAMP()),
    ('VIEWER', 'eval:read', UTC_TIMESTAMP());

-- ===== M7 受控 Workflow Studio（同步 V014） =====
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
