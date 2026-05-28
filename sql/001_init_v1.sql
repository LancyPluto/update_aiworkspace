SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS users (
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
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_users_type_status(user_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS roles (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code VARCHAR(64) NOT NULL UNIQUE,
  role_name VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_roles (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_role(user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS login_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  account VARCHAR(128) NOT NULL,
  user_type VARCHAR(32) NOT NULL,
  login_status VARCHAR(32) NOT NULL,
  fail_reason VARCHAR(255),
  ip_address VARCHAR(64),
  user_agent VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_login_user_created(user_id, created_at),
  KEY idx_login_account_created(account, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_categories (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category_code VARCHAR(64) NOT NULL UNIQUE,
  category_name VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_settings (
  setting_key VARCHAR(128) PRIMARY KEY,
  setting_value TEXT,
  setting_group VARCHAR(64) NOT NULL DEFAULT 'system',
  description VARCHAR(255),
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_tools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_code VARCHAR(128) NOT NULL UNIQUE,
  tool_name VARCHAR(128) NOT NULL,
  category_id BIGINT NOT NULL,
  description TEXT,
  cover_url VARCHAR(512),
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  estimated_credit_cost INT NOT NULL DEFAULT 0,
  model_config_id BIGINT NULL,
  created_by BIGINT,
  updated_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_tools_category(category_id),
  KEY idx_tools_status(status),
  KEY idx_tools_model_config(model_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_field_schemas (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  schema_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_schema(tool_id, schema_version),
  KEY idx_schema_tool_status(tool_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_field_schema_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  schema_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  field_type VARCHAR(32) NOT NULL,
  placeholder VARCHAR(255),
  options_json JSON,
  validation_json JSON,
  required TINYINT NOT NULL DEFAULT 0,
  execution_required TINYINT NOT NULL DEFAULT 0,
  user_required TINYINT NOT NULL DEFAULT 0,
  default_value VARCHAR(512),
  agent_fill_strategy VARCHAR(32) NOT NULL DEFAULT 'default',
  risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_schema_field(schema_id, field_key),
  KEY idx_schema_items_schema(schema_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_prompts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  prompt_code VARCHAR(128) NOT NULL,
  prompt_name VARCHAR(128) NOT NULL,
  active_version_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_prompt(tool_id, prompt_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_prompt_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  prompt_id BIGINT NOT NULL,
  version_no VARCHAR(32) NOT NULL,
  system_prompt TEXT,
  user_prompt_template MEDIUMTEXT NOT NULL,
  output_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME,
  UNIQUE KEY uk_prompt_version(prompt_id, version_no),
  KEY idx_prompt_versions_status(prompt_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_tasks (
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
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tasks_user_status(user_id, status),
  KEY idx_tasks_tool_status(tool_id, status),
  KEY idx_tasks_created(created_at),
  KEY idx_tasks_idem(user_id, idempotency_key),
  KEY idx_tasks_params(user_id, tool_id, params_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_task_inputs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  field_type VARCHAR(32) NOT NULL,
  value_text MEDIUMTEXT,
  value_json JSON,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task_inputs_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_task_logs (
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
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task_logs_task(task_id),
  KEY idx_task_logs_event(event_type),
  KEY idx_task_logs_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_result_resources (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  content_text MEDIUMTEXT,
  content_json JSON,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_result_task(task_id),
  KEY idx_result_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_outbox_events (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS credit_accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL UNIQUE,
  balance INT NOT NULL DEFAULT 0,
  frozen INT NOT NULL DEFAULT 0,
  total_granted INT NOT NULL DEFAULT 0,
  total_consumed INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS credit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  task_id BIGINT,
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
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_credit_idem(idempotency_key),
  KEY idx_credit_logs_user(user_id),
  KEY idx_credit_logs_task(task_id),
  KEY idx_credit_logs_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS worker_heartbeats (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  worker_id VARCHAR(128) NOT NULL UNIQUE,
  worker_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  status VARCHAR(32) NOT NULL DEFAULT 'ONLINE',
  last_heartbeat_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_operation_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  admin_id BIGINT NOT NULL,
  operation_type VARCHAR(64) NOT NULL,
  target_type VARCHAR(64),
  target_id BIGINT,
  content_json JSON,
  reason VARCHAR(512),
  ip_address VARCHAR(64),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_admin_ops_admin(admin_id),
  KEY idx_admin_ops_target(target_type, target_id),
  KEY idx_admin_ops_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO roles (role_code, role_name)
VALUES ('USER', 'User'), ('ADMIN', 'Admin')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

INSERT INTO tool_categories (category_code, category_name, sort_order, status)
VALUES
  ('copywriting', '文案生成', 1, 'ACTIVE'),
  ('agent', '智能体', 2, 'ACTIVE')
ON DUPLICATE KEY UPDATE category_name = VALUES(category_name), status = VALUES(status);
