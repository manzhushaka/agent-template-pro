SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'agent_conversation' AND column_name = 'active_agent_code') = 0,
    'ALTER TABLE agent_conversation ADD COLUMN active_agent_code VARCHAR(128) NULL AFTER status',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'agent_conversation' AND column_name = 'routing_version') = 0,
    'ALTER TABLE agent_conversation ADD COLUMN routing_version BIGINT NOT NULL DEFAULT 0 AFTER active_agent_code',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'agent_message' AND column_name = 'agent_code') = 0,
    'ALTER TABLE agent_message ADD COLUMN agent_code VARCHAR(128) NULL AFTER event_type, ADD COLUMN action_code VARCHAR(150) NULL AFTER agent_code',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'agent_pending_action' AND column_name = 'domain_code') = 0,
    'ALTER TABLE agent_pending_action ADD COLUMN domain_code VARCHAR(128) NULL AFTER conversation_id',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'agent_stream_event' AND column_name = 'agent_code') = 0,
    'ALTER TABLE agent_stream_event ADD COLUMN agent_code VARCHAR(128) NULL AFTER event_type, ADD COLUMN action_code VARCHAR(150) NULL AFTER agent_code',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'agent_audit_event' AND column_name = 'request_id') = 0,
    'ALTER TABLE agent_audit_event ADD COLUMN request_id VARCHAR(128) NULL AFTER task_id, ADD COLUMN domain_code VARCHAR(128) NULL AFTER request_id, ADD COLUMN action_code VARCHAR(150) NULL AFTER domain_code',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

UPDATE agent_pending_action
SET domain_code = SUBSTRING_INDEX(action_code, '.', 1)
WHERE domain_code IS NULL;

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
