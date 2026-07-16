SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ensure_workflow_provider_accounting;

DELIMITER $$
CREATE PROCEDURE ensure_workflow_provider_accounting()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND column_name = 'provider_cost_currency'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD COLUMN provider_cost_currency VARCHAR(8) NOT NULL DEFAULT 'CNY'
      AFTER vendor_cost_amount;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND column_name = 'outcome'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD COLUMN outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCESS';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND column_name = 'error_code'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD COLUMN error_code VARCHAR(64) NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND column_name = 'failure_stage'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD COLUMN failure_stage VARCHAR(64) NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND column_name = 'provider_error_code'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD COLUMN provider_error_code VARCHAR(128) NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND column_name = 'provider_request_id'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD COLUMN provider_request_id VARCHAR(128) NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND column_name = 'provider_charged'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD COLUMN provider_charged TINYINT NOT NULL DEFAULT 0;
  END IF;
END $$
DELIMITER ;

CALL ensure_workflow_provider_accounting();
DROP PROCEDURE IF EXISTS ensure_workflow_provider_accounting;
