SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS ensure_error_contract_column;
DROP PROCEDURE IF EXISTS ensure_error_contract_index;

DELIMITER $$
CREATE PROCEDURE ensure_error_contract_column(
  IN target_table VARCHAR(64),
  IN target_column VARCHAR(64),
  IN alter_statement TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = target_table
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = target_table
      AND column_name = target_column
  ) THEN
    SET @error_contract_ddl = alter_statement;
    PREPARE error_contract_stmt FROM @error_contract_ddl;
    EXECUTE error_contract_stmt;
    DEALLOCATE PREPARE error_contract_stmt;
  END IF;
END $$

CREATE PROCEDURE ensure_error_contract_index(
  IN target_table VARCHAR(64),
  IN target_index VARCHAR(64),
  IN alter_statement TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = target_table
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = target_table
      AND index_name = target_index
  ) THEN
    SET @error_contract_ddl = alter_statement;
    PREPARE error_contract_stmt FROM @error_contract_ddl;
    EXECUTE error_contract_stmt;
    DEALLOCATE PREPARE error_contract_stmt;
  END IF;
END $$
DELIMITER ;

CALL ensure_error_contract_column('ai_tasks', 'user_message',
  'ALTER TABLE ai_tasks ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('ai_tasks', 'developer_message',
  'ALTER TABLE ai_tasks ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('ai_tasks', 'failure_trace_id',
  'ALTER TABLE ai_tasks ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');
CALL ensure_error_contract_column('ai_tasks', 'provider_error_code',
  'ALTER TABLE ai_tasks ADD COLUMN provider_error_code VARCHAR(128) NULL AFTER failure_trace_id');
CALL ensure_error_contract_column('ai_tasks', 'provider_request_id',
  'ALTER TABLE ai_tasks ADD COLUMN provider_request_id VARCHAR(128) NULL AFTER provider_error_code');

CALL ensure_error_contract_column('ai_task_logs', 'user_message',
  'ALTER TABLE ai_task_logs ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('ai_task_logs', 'developer_message',
  'ALTER TABLE ai_task_logs ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('ai_task_logs', 'failure_trace_id',
  'ALTER TABLE ai_task_logs ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');

CALL ensure_error_contract_column('task_model_route_attempts', 'user_message',
  'ALTER TABLE task_model_route_attempts ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('task_model_route_attempts', 'developer_message',
  'ALTER TABLE task_model_route_attempts ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('task_model_route_attempts', 'failure_trace_id',
  'ALTER TABLE task_model_route_attempts ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');

CALL ensure_error_contract_column('agent_runs', 'user_message',
  'ALTER TABLE agent_runs ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('agent_runs', 'developer_message',
  'ALTER TABLE agent_runs ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('agent_runs', 'failure_trace_id',
  'ALTER TABLE agent_runs ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');

CALL ensure_error_contract_column('agent_tool_calls', 'user_message',
  'ALTER TABLE agent_tool_calls ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('agent_tool_calls', 'developer_message',
  'ALTER TABLE agent_tool_calls ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('agent_tool_calls', 'failure_trace_id',
  'ALTER TABLE agent_tool_calls ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');

CALL ensure_error_contract_column('workflow_runs', 'error_code',
  'ALTER TABLE workflow_runs ADD COLUMN error_code VARCHAR(64) NULL AFTER current_node_id');
CALL ensure_error_contract_column('workflow_runs', 'user_message',
  'ALTER TABLE workflow_runs ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('workflow_runs', 'developer_message',
  'ALTER TABLE workflow_runs ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('workflow_runs', 'failure_trace_id',
  'ALTER TABLE workflow_runs ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');

CALL ensure_error_contract_column('workflow_run_steps', 'error_code',
  'ALTER TABLE workflow_run_steps ADD COLUMN error_code VARCHAR(64) NULL AFTER output_json');
CALL ensure_error_contract_column('workflow_run_steps', 'user_message',
  'ALTER TABLE workflow_run_steps ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('workflow_run_steps', 'developer_message',
  'ALTER TABLE workflow_run_steps ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('workflow_run_steps', 'failure_trace_id',
  'ALTER TABLE workflow_run_steps ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');

CALL ensure_error_contract_column('workflow_step_attempts', 'user_message',
  'ALTER TABLE workflow_step_attempts ADD COLUMN user_message VARCHAR(255) NULL AFTER error_code');
CALL ensure_error_contract_column('workflow_step_attempts', 'developer_message',
  'ALTER TABLE workflow_step_attempts ADD COLUMN developer_message TEXT NULL AFTER user_message');
CALL ensure_error_contract_column('workflow_step_attempts', 'failure_trace_id',
  'ALTER TABLE workflow_step_attempts ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER developer_message');

CALL ensure_error_contract_index('ai_tasks', 'idx_ai_tasks_failure_trace',
  'ALTER TABLE ai_tasks ADD KEY idx_ai_tasks_failure_trace(failure_trace_id)');
CALL ensure_error_contract_index('ai_task_logs', 'idx_ai_task_logs_failure_trace',
  'ALTER TABLE ai_task_logs ADD KEY idx_ai_task_logs_failure_trace(failure_trace_id)');
CALL ensure_error_contract_index('task_model_route_attempts', 'idx_task_model_route_attempt_failure_trace',
  'ALTER TABLE task_model_route_attempts ADD KEY idx_task_model_route_attempt_failure_trace(failure_trace_id)');
CALL ensure_error_contract_index('agent_runs', 'idx_agent_runs_failure_trace',
  'ALTER TABLE agent_runs ADD KEY idx_agent_runs_failure_trace(failure_trace_id)');
CALL ensure_error_contract_index('agent_tool_calls', 'idx_agent_tool_calls_failure_trace',
  'ALTER TABLE agent_tool_calls ADD KEY idx_agent_tool_calls_failure_trace(failure_trace_id)');
CALL ensure_error_contract_index('workflow_runs', 'idx_workflow_runs_failure_trace',
  'ALTER TABLE workflow_runs ADD KEY idx_workflow_runs_failure_trace(failure_trace_id)');
CALL ensure_error_contract_index('workflow_run_steps', 'idx_workflow_run_steps_failure_trace',
  'ALTER TABLE workflow_run_steps ADD KEY idx_workflow_run_steps_failure_trace(failure_trace_id)');
CALL ensure_error_contract_index('workflow_step_attempts', 'idx_workflow_step_attempts_failure_trace',
  'ALTER TABLE workflow_step_attempts ADD KEY idx_workflow_step_attempts_failure_trace(failure_trace_id)');

DROP PROCEDURE IF EXISTS ensure_error_contract_column;
DROP PROCEDURE IF EXISTS ensure_error_contract_index;
