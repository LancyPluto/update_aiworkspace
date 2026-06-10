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

CALL add_column_if_missing('agent_runs', 'model_config_id', '`model_config_id` BIGINT NULL AFTER `intent`');
CALL add_column_if_missing('agent_runs', 'context_snapshot_id', '`context_snapshot_id` BIGINT NULL AFTER `source_user_message_id`');
CALL add_index_if_missing('agent_runs', 'idx_agent_runs_model_config', 'CREATE INDEX idx_agent_runs_model_config ON agent_runs(model_config_id)');
CALL add_index_if_missing('agent_runs', 'idx_agent_runs_context_snapshot', 'CREATE INDEX idx_agent_runs_context_snapshot ON agent_runs(context_snapshot_id)');

CREATE TABLE IF NOT EXISTS agent_context_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  workspace_id BIGINT NULL,
  model_config_id BIGINT NULL,
  model_provider_code VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  strategy VARCHAR(64) NOT NULL,
  max_history_messages INT NOT NULL DEFAULT 20,
  history_message_count INT NOT NULL DEFAULT 0,
  file_count INT NOT NULL DEFAULT 0,
  file_chunk_count INT NOT NULL DEFAULT 0,
  memory_item_count INT NOT NULL DEFAULT 0,
  estimated_input_tokens INT NOT NULL DEFAULT 0,
  snapshot_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_context_snapshots_run (run_id, id),
  KEY idx_agent_context_snapshots_session (session_id, id),
  KEY idx_agent_context_snapshots_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
