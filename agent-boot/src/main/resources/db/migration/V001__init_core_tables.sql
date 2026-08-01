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
    visitor_id VARCHAR(36) NOT NULL,
    graph_thread_id VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_agent_code VARCHAR(128) NULL,
    routing_version BIGINT NOT NULL DEFAULT 0,
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
    agent_code VARCHAR(128) NULL,
    action_code VARCHAR(150) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_conversation_sequence(conversation_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS agent_task (
    id VARCHAR(64) PRIMARY KEY,
    visitor_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    domain_code VARCHAR(100) NOT NULL,
    action_code VARCHAR(150) NOT NULL,
    status VARCHAR(64) NOT NULL,
    external_ref VARCHAR(200) NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_action_idempotency(action_code, idempotency_key),
    INDEX idx_status_updated(status, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_confirmation (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    confirmation_version INT NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    decision VARCHAR(32) NULL,
    confirmed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_task_confirmation_version(task_id, confirmation_version)
);

CREATE TABLE IF NOT EXISTS agent_audit_event (
    id VARCHAR(64) PRIMARY KEY,
    visitor_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(64) NULL,
    request_id VARCHAR(128) NULL,
    domain_code VARCHAR(128) NULL,
    action_code VARCHAR(150) NULL,
    event_type VARCHAR(100) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    metadata_json JSON NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_task_created(task_id, created_at),
    INDEX idx_visitor_created(visitor_id, created_at)
);

CREATE TABLE IF NOT EXISTS agent_pending_action (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    domain_code VARCHAR(128) NULL,
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
    request_id VARCHAR(128) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    agent_code VARCHAR(128) NULL,
    action_code VARCHAR(150) NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_stream_event_sequence(conversation_id, sequence_no),
    INDEX idx_stream_event_cursor(conversation_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS agent_route_decision (
    id VARCHAR(64) PRIMARY KEY,
    visitor_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    route_sequence INT NOT NULL DEFAULT 1,
    source_agent_code VARCHAR(128) NOT NULL,
    target_agent_code VARCHAR(128) NULL,
    route_type VARCHAR(32) NOT NULL,
    route_source VARCHAR(32) NOT NULL,
    confidence DECIMAL(5, 4) NULL,
    reason_code VARCHAR(64) NOT NULL,
    candidate_agents_json JSON NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_route_request_sequence(conversation_id, request_id, route_sequence),
    INDEX idx_route_target_created(target_agent_code, created_at),
    INDEX idx_route_conversation_created(conversation_id, created_at)
);
