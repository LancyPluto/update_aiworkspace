SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS agent_model_configs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  display_name VARCHAR(128) NOT NULL DEFAULT 'Default model',
  config_code VARCHAR(64) NOT NULL DEFAULT 'default',
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  base_url VARCHAR(512) NULL,
  api_key VARCHAR(512) NULL,
  minimax_group_id VARCHAR(128) NULL,
  timeout_seconds INT NOT NULL DEFAULT 60,
  enabled TINYINT NOT NULL DEFAULT 1,
  is_default TINYINT NOT NULL DEFAULT 0,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_model_configs_code (config_code),
  KEY idx_agent_model_configs_enabled (enabled, is_deleted, id),
  KEY idx_agent_model_configs_default (is_default, is_deleted, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

