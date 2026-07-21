SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ensure_model_account_routing_columns;

DELIMITER $$
CREATE PROCEDURE ensure_model_account_routing_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'model_vendor_accounts'
      AND column_name = 'health_message'
  ) THEN
    ALTER TABLE model_vendor_accounts
      ADD COLUMN health_message VARCHAR(512) NULL AFTER health_status;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'model_vendor_accounts'
      AND column_name = 'health_checked_at'
  ) THEN
    ALTER TABLE model_vendor_accounts
      ADD COLUMN health_checked_at DATETIME NULL AFTER health_message;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'model_vendor_accounts'
      AND column_name = 'load_balance_enabled'
  ) THEN
    ALTER TABLE model_vendor_accounts
      ADD COLUMN load_balance_enabled TINYINT NOT NULL DEFAULT 0 AFTER health_checked_at;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'model_vendor_accounts'
      AND column_name = 'load_balance_weight'
  ) THEN
    ALTER TABLE model_vendor_accounts
      ADD COLUMN load_balance_weight INT NOT NULL DEFAULT 100 AFTER load_balance_enabled;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'selected_model_config_id'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN selected_model_config_id BIGINT NULL AFTER model_config_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'selected_vendor_account_id'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN selected_vendor_account_id BIGINT NULL AFTER selected_model_config_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'current_route_attempt_id'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN current_route_attempt_id BIGINT NULL AFTER selected_vendor_account_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND index_name = 'idx_ai_tasks_selected_route'
  ) THEN
    ALTER TABLE ai_tasks
      ADD KEY idx_ai_tasks_selected_route(selected_vendor_account_id, status, id);
  END IF;
END $$
DELIMITER ;

CALL ensure_model_account_routing_columns();
DROP PROCEDURE IF EXISTS ensure_model_account_routing_columns;

CREATE TABLE IF NOT EXISTS task_model_route_attempts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  model_config_id BIGINT NOT NULL,
  vendor_account_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  delivery_state VARCHAR(32) NULL,
  failure_stage VARCHAR(64) NULL,
  error_code VARCHAR(64) NULL,
  error_message TEXT NULL,
  provider_error_code VARCHAR(128) NULL,
  provider_request_id VARCHAR(128) NULL,
  provider_charged TINYINT NULL,
  retry_after_seconds INT NULL,
  claim_token VARCHAR(128) NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_model_route_attempt(task_id, attempt_no),
  KEY idx_task_model_route_attempt_active(vendor_account_id, status, task_id),
  KEY idx_task_model_route_attempt_task(task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_model_route_state (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  vendor_account_id BIGINT NOT NULL,
  model_config_id BIGINT NOT NULL,
  in_flight_count INT NOT NULL DEFAULT 0,
  circuit_status VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
  consecutive_failures INT NOT NULL DEFAULT 0,
  cooldown_until DATETIME NULL,
  last_selected_at DATETIME NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_account_model_route_state_model(model_config_id),
  KEY idx_account_model_route_state_account(vendor_account_id, circuit_status),
  KEY idx_account_model_route_state_cooldown(circuit_status, cooldown_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
