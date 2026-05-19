CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  phone VARCHAR(32) UNIQUE,
  email VARCHAR(128) UNIQUE,
  nickname VARCHAR(64),
  user_type VARCHAR(32) NOT NULL DEFAULT 'USER',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE tool_categories (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category_code VARCHAR(64) NOT NULL UNIQUE,
  category_name VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE system_settings (
  setting_key VARCHAR(128) PRIMARY KEY,
  setting_value TEXT,
  setting_group VARCHAR(64) NOT NULL DEFAULT 'system',
  description VARCHAR(255),
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE ai_tools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_code VARCHAR(128) NOT NULL UNIQUE,
  tool_name VARCHAR(128) NOT NULL,
  category_id BIGINT NOT NULL,
  description TEXT,
  cover_url VARCHAR(512),
  tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  config_note TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  estimated_credit_cost INT NOT NULL DEFAULT 0,
  model_config_id BIGINT,
  template_id BIGINT,
  execution_handler VARCHAR(32),
  created_by BIGINT,
  updated_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE tool_templates (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_code VARCHAR(128) NOT NULL UNIQUE,
  template_name VARCHAR(128) NOT NULL,
  tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  execution_handler VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  config_note TEXT,
  default_system_prompt TEXT,
  default_user_prompt_template TEXT,
  default_output_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  handler_config_json TEXT,
  suggested_model_config_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  is_system TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tool_template_fields (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  field_type VARCHAR(32) NOT NULL,
  placeholder VARCHAR(255),
  options_json TEXT,
  validation_json TEXT,
  required TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tool_field_schemas (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  schema_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE tool_field_schema_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  schema_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  field_type VARCHAR(32) NOT NULL,
  placeholder VARCHAR(255),
  options_json JSON,
  validation_json JSON,
  required TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE ai_tasks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NOT NULL,
  field_schema_id BIGINT,
  prompt_version_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  progress TINYINT NOT NULL DEFAULT 0,
  progress_message VARCHAR(255),
  params_json JSON NOT NULL,
  params_hash VARCHAR(128),
  idempotency_key VARCHAR(128),
  estimated_credit_cost INT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  max_retry_count INT NOT NULL DEFAULT 1,
  error_code VARCHAR(64),
  error_message TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  queued_at DATETIME,
  started_at DATETIME,
  finished_at DATETIME,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE ai_result_resources (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  content_text MEDIUMTEXT,
  content_json JSON,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE task_outbox_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_task_outbox_status_retry (status, next_retry_at, id),
  UNIQUE KEY uk_task_outbox_task_event (task_id, event_type)
);

CREATE TABLE credit_accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL UNIQUE,
  balance INT NOT NULL DEFAULT 0,
  frozen INT NOT NULL DEFAULT 0,
  total_granted INT NOT NULL DEFAULT 0,
  total_consumed INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE credit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  task_id BIGINT,
  agent_run_id BIGINT,
  log_type VARCHAR(32) NOT NULL,
  amount INT NOT NULL DEFAULT 0,
  frozen_amount INT NOT NULL DEFAULT 0,
  balance_before INT NOT NULL,
  balance_after INT NOT NULL,
  frozen_before INT NOT NULL,
  frozen_after INT NOT NULL,
  idempotency_key VARCHAR(128),
  operator_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  operator_id BIGINT,
  reason VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tool_prompts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  prompt_code VARCHAR(128) NOT NULL,
  prompt_name VARCHAR(128) NOT NULL,
  active_version_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE tool_prompt_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  prompt_id BIGINT NOT NULL,
  version_no VARCHAR(32) NOT NULL,
  system_prompt TEXT,
  user_prompt_template MEDIUMTEXT NOT NULL,
  output_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME
);

CREATE TABLE ai_task_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  from_status VARCHAR(32),
  to_status VARCHAR(32),
  event_type VARCHAR(64) NOT NULL,
  message TEXT,
  error_code VARCHAR(64),
  error_message TEXT,
  operator_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  operator_id BIGINT,
  metadata_json JSON,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  workspace_id BIGINT,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_workspaces (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  workspace_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(owner_user_id, workspace_type)
);

CREATE TABLE agent_workspace_members (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(workspace_id, user_id)
);

CREATE TABLE agent_workspace_memory_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  memory_type VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL,
  content CLOB NOT NULL,
  source_run_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content_text CLOB NOT NULL,
  content_json JSON,
  run_id BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  intent VARCHAR(64),
  model_provider_code VARCHAR(64),
  model_name VARCHAR(128),
  estimated_credits INT NOT NULL DEFAULT 0,
  consumed_credits INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  error_message VARCHAR(512),
  started_at DATETIME,
  finished_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_run_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_text CLOB,
  event_json JSON,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_tool_calls (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  arguments_json JSON NOT NULL,
  result_json JSON,
  error_code VARCHAR(64),
  error_message VARCHAR(512),
  started_at DATETIME,
  finished_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_tool_preferences (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  auto_call_enabled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, tool_code)
);

CREATE TABLE agent_pending_tool_context (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  selected_tool_code VARCHAR(64),
  candidate_tool_codes_json CLOB,
  collected_arguments_json CLOB,
  missing_arguments_json CLOB,
  clarifying_question VARCHAR(2000),
  confirmation_required TINYINT DEFAULT 0,
  source VARCHAR(32) DEFAULT 'intent_router',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_tool_descriptor_extension (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  tool_code VARCHAR(64) NOT NULL UNIQUE,
  agent_enabled TINYINT NOT NULL DEFAULT 1,
  agent_recommendable TINYINT NOT NULL DEFAULT 1,
  agent_auto_callable TINYINT NOT NULL DEFAULT 0,
  confirmation_policy VARCHAR(32) DEFAULT 'auto',
  risk_level VARCHAR(16) DEFAULT 'low',
  keywords_json CLOB,
  example_prompts_json CLOB,
  applicable_scenarios_json CLOB,
  not_applicable_scenarios_json CLOB,
  result_schema_json CLOB,
  output_type VARCHAR(32) DEFAULT 'text',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(128),
  file_size BIGINT NOT NULL DEFAULT 0,
  storage_path VARCHAR(1024) NOT NULL,
  status VARCHAR(32) NOT NULL,
  extracted_text CLOB,
  error_message VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_file_chunks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content_text CLOB NOT NULL,
  metadata_json CLOB,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_model_configs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  display_name VARCHAR(128),
  config_code VARCHAR(64),
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  base_url VARCHAR(512),
  api_key VARCHAR(512),
  minimax_group_id VARCHAR(128),
  console_url VARCHAR(512),
  balance_url VARCHAR(512),
  docs_url VARCHAR(512),
  timeout_seconds INT NOT NULL DEFAULT 60,
  input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
  unit_price DECIMAL(18,8) NOT NULL DEFAULT 0,
  capabilities TEXT,
  enabled TINYINT NOT NULL DEFAULT 1,
  is_default TINYINT NOT NULL DEFAULT 0,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE billing_usage_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_type VARCHAR(32) NOT NULL,
  source_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  model_config_id BIGINT,
  provider VARCHAR(64),
  model_name VARCHAR(128),
  prompt_tokens INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  total_tokens INT NOT NULL DEFAULT 0,
  input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
  billable_units INT NOT NULL DEFAULT 0,
  unit_price DECIMAL(18,8) NOT NULL DEFAULT 0,
  cost_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  charged_credits INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Default model config for TEXT_GENERATION tools (tests create tools without model_config_id)
INSERT INTO agent_model_configs (
  display_name,
  config_code,
  provider,
  model_name,
  base_url,
  timeout_seconds,
  billing_unit,
  unit_price,
  capabilities,
  enabled,
  is_default,
  is_deleted
) VALUES (
  'Test text generation',
  'default_text_generation',
  'minimax',
  'MiniMax-M2.7',
  'https://api.minimaxi.com/v1',
  120,
  'TOKEN_PER_M',
  0,
  '["TEXT_GENERATION"]',
  1,
  1,
  0
);
