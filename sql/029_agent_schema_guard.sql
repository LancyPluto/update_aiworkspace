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

CALL add_column_if_missing('agent_files', 'attached_run_id', '`attached_run_id` BIGINT NULL');
CALL add_index_if_missing('agent_files', 'idx_agent_files_attached_run', 'CREATE INDEX idx_agent_files_attached_run ON agent_files(session_id, attached_run_id, id)');

CALL add_column_if_missing('agent_messages', 'status', '`status` VARCHAR(32) NOT NULL DEFAULT ''ACTIVE''');
CALL add_column_if_missing('agent_messages', 'superseded_at', '`superseded_at` DATETIME NULL');
CALL add_column_if_missing('agent_messages', 'edited_at', '`edited_at` DATETIME NULL');
CALL add_index_if_missing('agent_messages', 'idx_agent_messages_session_active', 'CREATE INDEX idx_agent_messages_session_active ON agent_messages(session_id, status, id)');

CALL add_column_if_missing('agent_runs', 'parent_run_id', '`parent_run_id` BIGINT NULL');
CALL add_column_if_missing('agent_runs', 'source_user_message_id', '`source_user_message_id` BIGINT NULL');
CALL add_column_if_missing('agent_runs', 'client_request_id', '`client_request_id` VARCHAR(64) NULL');
CALL add_column_if_missing('agent_runs', 'model_config_id', '`model_config_id` BIGINT NULL');
CALL add_column_if_missing('agent_runs', 'context_snapshot_id', '`context_snapshot_id` BIGINT NULL');
CALL add_index_if_missing('agent_runs', 'uk_agent_runs_user_client', 'CREATE UNIQUE INDEX uk_agent_runs_user_client ON agent_runs(user_id, client_request_id)');
CALL add_index_if_missing('agent_runs', 'idx_agent_runs_session_user_id', 'CREATE INDEX idx_agent_runs_session_user_id ON agent_runs(session_id, user_id, id)');
CALL add_index_if_missing('agent_runs', 'idx_agent_runs_model_config', 'CREATE INDEX idx_agent_runs_model_config ON agent_runs(model_config_id)');
CALL add_index_if_missing('agent_runs', 'idx_agent_runs_context_snapshot', 'CREATE INDEX idx_agent_runs_context_snapshot ON agent_runs(context_snapshot_id)');

CALL add_column_if_missing('agent_tool_calls', 'task_id', '`task_id` BIGINT NULL');
CALL add_index_if_missing('agent_tool_calls', 'idx_agent_tool_calls_task_id', 'CREATE INDEX idx_agent_tool_calls_task_id ON agent_tool_calls(task_id)');
CALL add_index_if_missing('agent_tool_calls', 'idx_agent_tool_calls_context_recent', 'CREATE INDEX idx_agent_tool_calls_context_recent ON agent_tool_calls(user_id, status, id)');

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
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CALL add_index_if_missing('agent_context_snapshots', 'idx_agent_context_snapshots_run', 'CREATE INDEX idx_agent_context_snapshots_run ON agent_context_snapshots(run_id, id)');
CALL add_index_if_missing('agent_context_snapshots', 'idx_agent_context_snapshots_session', 'CREATE INDEX idx_agent_context_snapshots_session ON agent_context_snapshots(session_id, id)');
CALL add_index_if_missing('agent_context_snapshots', 'idx_agent_context_snapshots_user', 'CREATE INDEX idx_agent_context_snapshots_user ON agent_context_snapshots(user_id, id)');

CALL add_column_if_missing('agent_model_configs', 'agent_enabled', '`agent_enabled` TINYINT NOT NULL DEFAULT 1');
CALL add_index_if_missing('agent_model_configs', 'idx_agent_model_configs_agent_enabled', 'CREATE INDEX idx_agent_model_configs_agent_enabled ON agent_model_configs(agent_enabled, enabled, is_deleted, is_default, id)');

CALL add_column_if_missing('agent_tool_descriptor_extension', 'health_status', '`health_status` VARCHAR(32) NOT NULL DEFAULT ''UNKNOWN''');
CALL add_column_if_missing('agent_tool_descriptor_extension', 'health_message', '`health_message` VARCHAR(512) NULL');
CALL add_column_if_missing('agent_tool_descriptor_extension', 'health_checked_at', '`health_checked_at` DATETIME NULL');
CALL add_index_if_missing('agent_tool_descriptor_extension', 'idx_agent_tool_health', 'CREATE INDEX idx_agent_tool_health ON agent_tool_descriptor_extension(agent_enabled, health_status)');

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
