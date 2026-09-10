SET @has_idempotency_key := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_tool_calls' AND COLUMN_NAME = 'idempotency_key'
);
SET @sql := IF(@has_idempotency_key = 0,
  'ALTER TABLE agent_tool_calls ADD COLUMN idempotency_key VARCHAR(180) NULL AFTER tool_code', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idempotency_index := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_tool_calls'
    AND INDEX_NAME = 'uk_agent_tool_calls_run_idempotency'
);
SET @sql := IF(@has_idempotency_index = 0,
  'CREATE UNIQUE INDEX uk_agent_tool_calls_run_idempotency ON agent_tool_calls(run_id, idempotency_key)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
