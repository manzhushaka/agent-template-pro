SET @mcp_schema := DATABASE();

SET @mcp_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @mcp_schema AND table_name = 'agent_mcp_server' AND column_name = 'stdio_arguments_json') = 0,
    'ALTER TABLE agent_mcp_server ADD COLUMN stdio_arguments_json JSON NULL AFTER command_ref',
    'SELECT 1'
);
PREPARE mcp_statement FROM @mcp_sql;
EXECUTE mcp_statement;
DEALLOCATE PREPARE mcp_statement;

SET @mcp_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @mcp_schema AND table_name = 'agent_mcp_server' AND column_name = 'timeout_ms') = 0,
    'ALTER TABLE agent_mcp_server ADD COLUMN timeout_ms INT NOT NULL DEFAULT 5000 AFTER secret_ref_id',
    'SELECT 1'
);
PREPARE mcp_statement FROM @mcp_sql;
EXECUTE mcp_statement;
DEALLOCATE PREPARE mcp_statement;

SET @mcp_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @mcp_schema AND table_name = 'agent_mcp_sync_run' AND column_name = 'active_sync_key') = 0,
    'ALTER TABLE agent_mcp_sync_run ADD COLUMN active_sync_key CHAR(36) NULL AFTER mcp_server_id',
    'SELECT 1'
);
PREPARE mcp_statement FROM @mcp_sql;
EXECUTE mcp_statement;
DEALLOCATE PREPARE mcp_statement;

SET @mcp_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @mcp_schema AND table_name = 'agent_mcp_sync_run' AND column_name = 'lease_until') = 0,
    'ALTER TABLE agent_mcp_sync_run ADD COLUMN lease_until DATETIME(6) NULL AFTER active_sync_key',
    'SELECT 1'
);
PREPARE mcp_statement FROM @mcp_sql;
EXECUTE mcp_statement;
DEALLOCATE PREPARE mcp_statement;

UPDATE agent_mcp_sync_run
SET lease_until = DATE_ADD(started_at, INTERVAL 15 MINUTE)
WHERE active_sync_key IS NOT NULL AND lease_until IS NULL;

SET @mcp_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = @mcp_schema AND table_name = 'agent_mcp_sync_run' AND index_name = 'uk_mcp_sync_active') = 0,
    'ALTER TABLE agent_mcp_sync_run ADD UNIQUE KEY uk_mcp_sync_active(active_sync_key)',
    'SELECT 1'
);
PREPARE mcp_statement FROM @mcp_sql;
EXECUTE mcp_statement;
DEALLOCATE PREPARE mcp_statement;
