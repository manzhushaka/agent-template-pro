DROP PROCEDURE IF EXISTS ensure_knowledge_index_fencing;

DELIMITER $$

CREATE PROCEDURE ensure_knowledge_index_fencing()
BEGIN
    DECLARE lease_token_exists INT DEFAULT 0;
    DECLARE lease_epoch_exists INT DEFAULT 0;
    DECLARE lease_index_exists INT DEFAULT 0;

    SELECT COUNT(*) INTO lease_token_exists
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_knowledge_index_job'
      AND column_name = 'lease_token';

    IF lease_token_exists = 0 THEN
        SET @knowledge_index_fencing_sql =
            'ALTER TABLE agent_knowledge_index_job ADD COLUMN lease_token CHAR(36) NULL AFTER lease_owner';
        PREPARE knowledge_index_fencing_statement FROM @knowledge_index_fencing_sql;
        EXECUTE knowledge_index_fencing_statement;
        DEALLOCATE PREPARE knowledge_index_fencing_statement;
    END IF;

    SELECT COUNT(*) INTO lease_epoch_exists
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_knowledge_index_job'
      AND column_name = 'lease_epoch';

    IF lease_epoch_exists = 0 THEN
        SET @knowledge_index_fencing_sql =
            'ALTER TABLE agent_knowledge_index_job ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 0 AFTER lease_token';
        PREPARE knowledge_index_fencing_statement FROM @knowledge_index_fencing_sql;
        EXECUTE knowledge_index_fencing_statement;
        DEALLOCATE PREPARE knowledge_index_fencing_statement;
    END IF;

    SELECT COUNT(*) INTO lease_index_exists
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_knowledge_index_job'
      AND index_name = 'idx_knowledge_index_lease';

    IF lease_index_exists = 0 THEN
        SET @knowledge_index_fencing_sql =
            'ALTER TABLE agent_knowledge_index_job ADD INDEX idx_knowledge_index_lease(lease_owner, lease_token, lease_until)';
        PREPARE knowledge_index_fencing_statement FROM @knowledge_index_fencing_sql;
        EXECUTE knowledge_index_fencing_statement;
        DEALLOCATE PREPARE knowledge_index_fencing_statement;
    END IF;
END$$

DELIMITER ;

CALL ensure_knowledge_index_fencing();
DROP PROCEDURE ensure_knowledge_index_fencing;

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
