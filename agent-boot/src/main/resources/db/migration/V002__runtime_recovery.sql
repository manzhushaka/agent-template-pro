ALTER TABLE agent_conversation
    ADD COLUMN event_sequence BIGINT NOT NULL DEFAULT 0 AFTER status;

ALTER TABLE agent_task
    ADD COLUMN input_json JSON NOT NULL AFTER idempotency_key,
    ADD COLUMN confirmation_version INT NOT NULL DEFAULT 0 AFTER input_json,
    ADD COLUMN confirmation_snapshot_hash CHAR(64) NULL AFTER confirmation_version;

CREATE TABLE IF NOT EXISTS agent_pending_action (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    action_code VARCHAR(150) NOT NULL,
    input_json JSON NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_pending_expiry(expires_at)
);

CREATE TABLE IF NOT EXISTS agent_stream_event (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_stream_event_sequence(conversation_id, sequence_no),
    INDEX idx_stream_event_cursor(conversation_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS agent_tool_execution (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NULL,
    conversation_id VARCHAR(64) NOT NULL,
    tool_code VARCHAR(150) NOT NULL,
    tool_version_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    input_summary JSON NULL,
    output_summary JSON NULL,
    external_ref VARCHAR(200) NULL,
    trace_id VARCHAR(128) NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_tool_execution_task(task_id, created_at),
    INDEX idx_tool_execution_trace(trace_id)
);

CREATE TABLE IF NOT EXISTS agent_task_outbox (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME NULL,
    published_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_outbox_dispatch(status, available_at, created_at)
);
