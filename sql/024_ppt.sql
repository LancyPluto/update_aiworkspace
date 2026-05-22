-- PPT 生成工具：项目绑定与分步计费日志
CREATE TABLE IF NOT EXISTS ppt_project_bindings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NOT NULL,
  banana_project_id VARCHAR(64) NOT NULL,
  creation_type VARCHAR(32) NOT NULL,
  title VARCHAR(255) NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_banana_project (banana_project_id),
  KEY idx_user_tool (user_id, tool_id),
  KEY idx_user_updated (user_id, updated_at)
);

CREATE TABLE IF NOT EXISTS ppt_step_billing_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  step_code VARCHAR(64) NOT NULL,
  credits_charged INT NOT NULL,
  credit_log_id BIGINT NULL,
  client_request_id VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_binding (binding_id),
  KEY idx_user_created (user_id, created_at),
  UNIQUE KEY uk_binding_step_request (binding_id, step_code, client_request_id)
);
