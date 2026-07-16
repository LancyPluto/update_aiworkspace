SET NAMES utf8mb4;

ALTER TABLE ai_tasks
  ADD UNIQUE KEY uk_ai_tasks_user_idempotency(user_id, idempotency_key);

ALTER TABLE ai_tools
  ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT' AFTER execution_handler,
  ADD COLUMN billing_mode VARCHAR(32) NOT NULL DEFAULT 'FIXED' AFTER execution_mode,
  ADD COLUMN agent_surface_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER billing_mode,
  ADD COLUMN minimum_required_credits INT NOT NULL DEFAULT 0 AFTER agent_surface_enabled;

ALTER TABLE tool_workflows
  ADD COLUMN draft_revision BIGINT NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN published_version_id BIGINT NULL AFTER draft_revision,
  ADD COLUMN execution_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER published_version_id;

ALTER TABLE tool_workflow_versions
  ADD COLUMN canonical_dsl_json MEDIUMTEXT NULL AFTER config_json,
  ADD COLUMN dsl_version VARCHAR(32) NULL AFTER canonical_dsl_json,
  ADD COLUMN node_registry_version VARCHAR(32) NULL AFTER dsl_version,
  ADD COLUMN dsl_hash CHAR(64) NULL AFTER node_registry_version,
  ADD COLUMN input_schema_snapshot_json MEDIUMTEXT NULL AFTER dsl_hash,
  ADD COLUMN dependency_manifest_json MEDIUMTEXT NULL AFTER input_schema_snapshot_json,
  ADD COLUMN billing_policy_json MEDIUMTEXT NULL AFTER dependency_manifest_json,
  ADD COLUMN risk_policy_json MEDIUMTEXT NULL AFTER billing_policy_json,
  ADD COLUMN source_draft_revision BIGINT NULL AFTER risk_policy_json,
  ADD COLUMN published_at DATETIME NULL AFTER source_draft_revision,
  ADD COLUMN published_by BIGINT NULL AFTER published_at,
  ADD KEY idx_workflow_version_dsl_hash(workflow_id, dsl_hash);

ALTER TABLE workflow_runs
  ADD COLUMN workflow_version_id BIGINT NULL AFTER workflow_version,
  ADD COLUMN launch_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_TASK' AFTER root_task_id,
  ADD COLUMN client_request_id VARCHAR(128) NULL AFTER launch_source,
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN cancellation_generation BIGINT NOT NULL DEFAULT 0 AFTER revision,
  ADD COLUMN current_step_id BIGINT NULL AFTER current_node_id,
  ADD COLUMN billing_status VARCHAR(32) NOT NULL DEFAULT 'CLEAR' AFTER current_step_id,
  ADD COLUMN started_at DATETIME NULL AFTER error_message,
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at,
  ADD UNIQUE KEY uk_workflow_run_root_task(root_task_id),
  ADD UNIQUE KEY uk_workflow_run_user_request(user_id, client_request_id),
  ADD KEY idx_workflow_run_version(workflow_version_id);

ALTER TABLE workflow_run_steps
  ADD COLUMN sequence_no INT NOT NULL DEFAULT 0 AFTER node_id,
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER attempt,
  ADD COLUMN current_attempt_id BIGINT NULL AFTER attempt_count;

CREATE TABLE IF NOT EXISTS workflow_step_attempts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  step_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  child_task_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  claim_token VARCHAR(128) NOT NULL,
  provider_code VARCHAR(64) NULL,
  provider_request_id VARCHAR(128) NULL,
  input_json MEDIUMTEXT NULL,
  output_json MEDIUMTEXT NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(2000) NULL,
  lease_expires_at DATETIME NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_attempt_step_no(step_id, attempt_no),
  UNIQUE KEY uk_workflow_attempt_child_task(child_task_id),
  UNIQUE KEY uk_workflow_attempt_claim_token(claim_token),
  UNIQUE KEY uk_workflow_attempt_provider_request(provider_code, provider_request_id),
  KEY idx_workflow_attempt_status_lease(status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_step_charges (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  step_id BIGINT NOT NULL,
  attempt_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
  reserved_credits INT NOT NULL,
  charged_credits INT NOT NULL DEFAULT 0,
  provider_cost DECIMAL(18,6) NULL,
  provider_cost_currency VARCHAR(8) NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  credit_log_id BIGINT NULL,
  billing_usage_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_charge_attempt(attempt_id),
  UNIQUE KEY uk_workflow_charge_idempotency(idempotency_key),
  KEY idx_workflow_charge_run_status(run_id, status),
  KEY idx_workflow_charge_user_created(user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_confirmations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  step_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  parameter_hash CHAR(64) NOT NULL,
  allowed_actions_json MEDIUMTEXT NOT NULL,
  decision VARCHAR(32) NULL,
  feedback_json MEDIUMTEXT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_confirmation_token(token_hash),
  KEY idx_workflow_confirmation_run_status(run_id, status),
  KEY idx_workflow_confirmation_user_status(user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE ai_tools
SET execution_mode = 'WORKFLOW',
    billing_mode = 'WORKFLOW_STEP',
    agent_surface_enabled = 1
WHERE tool_code = 'ai_comic_drama_agent';
