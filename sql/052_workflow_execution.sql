SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NOT NULL,
  workflow_id BIGINT NOT NULL,
  workflow_version INT NOT NULL DEFAULT 1,
  root_task_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  input_json MEDIUMTEXT,
  context_json MEDIUMTEXT,
  current_node_id VARCHAR(64),
  error_message VARCHAR(2000),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME NULL,
  KEY idx_workflow_run_root_task (root_task_id),
  KEY idx_workflow_run_user_tool (user_id, tool_id, created_at),
  KEY idx_workflow_run_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_run_steps (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  node_id VARCHAR(64) NOT NULL,
  node_def_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  task_id BIGINT NULL,
  attempt INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 2,
  input_json MEDIUMTEXT,
  output_json MEDIUMTEXT,
  error_message VARCHAR(2000),
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  UNIQUE KEY uk_run_node (run_id, node_id),
  KEY idx_workflow_step_task (task_id),
  KEY idx_workflow_step_run_status (run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
