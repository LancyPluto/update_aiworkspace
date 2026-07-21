SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS model_account_routing_pools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  vendor_code VARCHAR(64) NOT NULL,
  pool_name VARCHAR(128) NOT NULL,
  pool_key VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_model_account_routing_pool_vendor_key(vendor_code, pool_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS ensure_model_account_routing_pool_columns;

DELIMITER $$
CREATE PROCEDURE ensure_model_account_routing_pool_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'model_vendor_accounts'
      AND column_name = 'routing_pool_id'
  ) THEN
    ALTER TABLE model_vendor_accounts
      ADD COLUMN routing_pool_id BIGINT NULL AFTER load_balance_weight;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'model_vendor_accounts'
      AND index_name = 'idx_model_vendor_accounts_routing_pool'
  ) THEN
    ALTER TABLE model_vendor_accounts
      ADD KEY idx_model_vendor_accounts_routing_pool(routing_pool_id, enabled, is_deleted);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'routing_pool_id'
  ) THEN
    ALTER TABLE agent_model_configs
      ADD COLUMN routing_pool_id BIGINT NULL AFTER vendor_account_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND index_name = 'idx_agent_model_configs_routing_pool'
  ) THEN
    ALTER TABLE agent_model_configs
      ADD KEY idx_agent_model_configs_routing_pool(routing_pool_id, enabled, is_deleted);
  END IF;
END $$
DELIMITER ;

CALL ensure_model_account_routing_pool_columns();
DROP PROCEDURE IF EXISTS ensure_model_account_routing_pool_columns;

UPDATE model_vendor_accounts
SET load_balance_enabled = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE load_balance_enabled = 1
  AND routing_pool_id IS NULL;
