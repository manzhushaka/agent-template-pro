SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task' AND column_name = 'confirmation_expires_at') = 0,
    'ALTER TABLE agent_task ADD COLUMN confirmation_expires_at DATETIME(6) NULL AFTER confirmation_snapshot_hash',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task' AND column_name = 'result_summary') = 0,
    'ALTER TABLE agent_task ADD COLUMN result_summary VARCHAR(1000) NULL AFTER confirmation_expires_at',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task' AND column_name = 'last_error_code') = 0,
    'ALTER TABLE agent_task ADD COLUMN last_error_code VARCHAR(100) NULL AFTER result_summary',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task' AND column_name = 'execution_lease_until') = 0,
    'ALTER TABLE agent_task ADD COLUMN execution_lease_until DATETIME(6) NULL AFTER last_error_code',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task' AND column_name = 'next_recovery_at') = 0,
    'ALTER TABLE agent_task ADD COLUMN next_recovery_at DATETIME(6) NULL AFTER execution_lease_until',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task' AND column_name = 'recovery_attempts') = 0,
    'ALTER TABLE agent_task ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0 AFTER next_recovery_at',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

ALTER TABLE agent_task MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
     AND table_name = 'agent_task' AND index_name = 'idx_task_recovery') = 0,
    'ALTER TABLE agent_task ADD INDEX idx_task_recovery(status, next_recovery_at, execution_lease_until)',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_confirmation' AND column_name = 'expires_at') = 0,
    'ALTER TABLE agent_confirmation ADD COLUMN expires_at DATETIME(6) NULL AFTER snapshot_hash',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_confirmation' AND column_name = 'request_id') = 0,
    'ALTER TABLE agent_confirmation ADD COLUMN request_id VARCHAR(128) NULL AFTER decision',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_confirmation' AND column_name = 'decided_at') = 0,
    'ALTER TABLE agent_confirmation ADD COLUMN decided_at DATETIME(6) NULL AFTER request_id',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

UPDATE agent_confirmation confirmation_record
JOIN agent_task task_record ON task_record.id = confirmation_record.task_id
SET confirmation_record.expires_at = COALESCE(confirmation_record.expires_at, DATE_ADD(task_record.created_at, INTERVAL 15 MINUTE)),
    task_record.confirmation_expires_at = COALESCE(task_record.confirmation_expires_at, DATE_ADD(task_record.created_at, INTERVAL 15 MINUTE));

ALTER TABLE agent_confirmation MODIFY COLUMN expires_at DATETIME(6) NOT NULL;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task_outbox' AND column_name = 'lease_owner') = 0,
    'ALTER TABLE agent_task_outbox ADD COLUMN lease_owner VARCHAR(128) NULL AFTER available_at',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task_outbox' AND column_name = 'lease_until') = 0,
    'ALTER TABLE agent_task_outbox ADD COLUMN lease_until DATETIME(6) NULL AFTER lease_owner',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'agent_task_outbox' AND column_name = 'last_error') = 0,
    'ALTER TABLE agent_task_outbox ADD COLUMN last_error VARCHAR(100) NULL AFTER lease_until',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
     AND table_name = 'agent_task_outbox' AND index_name = 'idx_outbox_dispatch') > 0,
    'ALTER TABLE agent_task_outbox DROP INDEX idx_outbox_dispatch',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

ALTER TABLE agent_task_outbox ADD INDEX idx_outbox_dispatch(status, available_at, lease_until, created_at);

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
