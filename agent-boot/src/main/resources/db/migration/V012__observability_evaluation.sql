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

INSERT INTO agent_admin_permission (code, display_name, created_at, updated_at) VALUES
    ('trace:read', '查看 Trace 与可观测性指标', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('eval:read', '查看数据集、评估器与实验', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('eval:write', '编辑数据集、评估器与实验', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('eval:run', '启动、停止与重试评估实验', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE code = agent_admin_permission.code;

INSERT IGNORE INTO agent_admin_role_permission (role_code, permission_code, created_at)
SELECT 'ADMIN', code, UTC_TIMESTAMP() FROM agent_admin_permission
WHERE code LIKE 'trace:%' OR code LIKE 'eval:%';

INSERT IGNORE INTO agent_admin_role_permission (role_code, permission_code, created_at) VALUES
    ('OPERATOR', 'trace:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'eval:read', UTC_TIMESTAMP()),
    ('OPERATOR', 'eval:write', UTC_TIMESTAMP()),
    ('OPERATOR', 'eval:run', UTC_TIMESTAMP()),
    ('VIEWER', 'trace:read', UTC_TIMESTAMP()),
    ('VIEWER', 'eval:read', UTC_TIMESTAMP());
