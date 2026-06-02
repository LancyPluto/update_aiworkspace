SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS model_vendor_accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  vendor_code VARCHAR(64) NOT NULL COMMENT '展示用厂商编码',
  account_name VARCHAR(128) NOT NULL DEFAULT '默认账户',
  base_url VARCHAR(512) NULL,
  api_key VARCHAR(1024) NULL,
  extra_auth_json TEXT NULL,
  console_url VARCHAR(512) NULL,
  balance_url VARCHAR(512) NULL,
  balance_query_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  balance_amount DECIMAL(18,4) NULL,
  balance_currency VARCHAR(8) NULL DEFAULT 'CNY',
  balance_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  balance_low_threshold DECIMAL(18,4) NULL,
  balance_updated_at DATETIME NULL,
  balance_error_message VARCHAR(512) NULL,
  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_vendor_accounts_vendor (vendor_code, enabled, is_deleted),
  KEY idx_vendor_accounts_balance_status (balance_status, enabled, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE agent_model_configs
  ADD COLUMN vendor_account_id BIGINT NULL COMMENT '所属厂商账户' AFTER id;

CREATE INDEX idx_agent_model_configs_vendor_account
  ON agent_model_configs (vendor_account_id, enabled, is_deleted);
