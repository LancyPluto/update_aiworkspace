SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS add_index_if_missing $$
CREATE PROCEDURE add_index_if_missing(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = p_index_ddl;
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL add_column_if_missing('agent_messages', 'status', '`status` VARCHAR(32) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''ACTIVE|SUPERSEDED''');
CALL add_column_if_missing('agent_messages', 'superseded_at', '`superseded_at` DATETIME NULL COMMENT ''superseded time''');
CALL add_index_if_missing('agent_messages', 'idx_agent_messages_session_active', 'CREATE INDEX idx_agent_messages_session_active ON agent_messages (session_id, status, id)');

CALL add_column_if_missing('agent_runs', 'parent_run_id', '`parent_run_id` BIGINT NULL COMMENT ''source run for regeneration''');
CALL add_column_if_missing('agent_runs', 'source_user_message_id', '`source_user_message_id` BIGINT NULL COMMENT ''source user message id''');
CALL add_column_if_missing('agent_runs', 'client_request_id', '`client_request_id` VARCHAR(64) NULL COMMENT ''client idempotency key''');
CALL add_index_if_missing('agent_runs', 'uk_agent_runs_user_client', 'CREATE UNIQUE INDEX uk_agent_runs_user_client ON agent_runs (user_id, client_request_id)');

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
