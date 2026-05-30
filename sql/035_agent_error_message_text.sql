SET NAMES utf8mb4;

ALTER TABLE agent_runs
  MODIFY COLUMN error_message TEXT NULL;

ALTER TABLE agent_tool_calls
  MODIFY COLUMN error_message TEXT NULL;

ALTER TABLE agent_files
  MODIFY COLUMN error_message TEXT NULL;
