-- Upgrade installations that previously stored a single mutable head row.
SET @has_namespace := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_langgraph_checkpoints' AND COLUMN_NAME = 'checkpoint_ns');
SET @sql := IF(@has_namespace = 0, 'ALTER TABLE agent_langgraph_checkpoints ADD COLUMN checkpoint_ns VARCHAR(255) NOT NULL DEFAULT '''' AFTER thread_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_old_unique := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_langgraph_checkpoints' AND INDEX_NAME = 'uk_agent_langgraph_checkpoint_thread');
SET @sql := IF(@has_old_unique = 1, 'ALTER TABLE agent_langgraph_checkpoints DROP INDEX uk_agent_langgraph_checkpoint_thread', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_new_unique := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_langgraph_checkpoints' AND INDEX_NAME = 'uk_agent_langgraph_checkpoint');
SET @sql := IF(@has_new_unique = 0,
  'ALTER TABLE agent_langgraph_checkpoints ADD UNIQUE KEY uk_agent_langgraph_checkpoint (run_id, thread_id, checkpoint_ns, checkpoint_id), ADD KEY idx_agent_langgraph_checkpoint_head (run_id, thread_id, checkpoint_ns, id DESC)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS agent_langgraph_checkpoint_writes (
  id BIGINT NOT NULL AUTO_INCREMENT, run_id BIGINT NOT NULL, thread_id VARCHAR(128) NOT NULL,
  checkpoint_ns VARCHAR(255) NOT NULL DEFAULT '', checkpoint_id VARCHAR(128) NOT NULL,
  task_id VARCHAR(128) NOT NULL, task_path VARCHAR(512) NOT NULL DEFAULT '', write_index INT NOT NULL,
  channel_name VARCHAR(255) NOT NULL, value_type VARCHAR(128) NOT NULL, value_base64 MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL, PRIMARY KEY (id),
  UNIQUE KEY uk_agent_langgraph_write (run_id, thread_id, checkpoint_ns, checkpoint_id, task_id, write_index),
  KEY idx_agent_langgraph_write_checkpoint (run_id, thread_id, checkpoint_ns, checkpoint_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
