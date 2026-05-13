CREATE TABLE IF NOT EXISTS agent_model_configs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  base_url VARCHAR(512) NULL,
  api_key VARCHAR(512) NULL,
  minimax_group_id VARCHAR(128) NULL,
  timeout_seconds INT NOT NULL DEFAULT 60,
  input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_agent_model_configs_enabled (enabled, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
