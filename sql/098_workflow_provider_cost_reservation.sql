SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_provider_cost_budget_days (
  budget_date DATE PRIMARY KEY,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS ensure_workflow_provider_cost_reservation;

DELIMITER $$
CREATE PROCEDURE ensure_workflow_provider_cost_reservation()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_runs'
      AND column_name = 'provider_cost_reserved_cny'
  ) THEN
    ALTER TABLE workflow_runs
      ADD COLUMN provider_cost_reserved_cny DECIMAL(18,6) NOT NULL DEFAULT 0
      AFTER billing_status;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_runs'
      AND index_name = 'idx_workflow_run_provider_cost_reservation'
  ) THEN
    ALTER TABLE workflow_runs
      ADD KEY idx_workflow_run_provider_cost_reservation(
        status,
        provider_cost_reserved_cny
      );
  END IF;
END $$
DELIMITER ;

CALL ensure_workflow_provider_cost_reservation();
DROP PROCEDURE IF EXISTS ensure_workflow_provider_cost_reservation;
