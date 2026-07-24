SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE ai_tasks
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);

UPDATE ai_task_logs
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);

UPDATE task_model_route_attempts
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);

UPDATE agent_runs
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);

UPDATE agent_tool_calls
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);

UPDATE workflow_runs
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);

UPDATE workflow_run_steps
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);

UPDATE workflow_step_attempts
SET developer_message = NULL
WHERE failure_trace_id IS NULL
  AND error_message IS NOT NULL
  AND BINARY developer_message = BINARY LEFT(error_message, 2000);
