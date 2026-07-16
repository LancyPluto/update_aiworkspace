SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ensure_workflow_provider_cost_gate_index;

DELIMITER $$
CREATE PROCEDURE ensure_workflow_provider_cost_gate_index()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'billing_usage_logs'
      AND index_name = 'idx_billing_usage_workflow_provider_cost'
  ) THEN
    ALTER TABLE billing_usage_logs
      ADD KEY idx_billing_usage_workflow_provider_cost(
        source_type,
        provider_charged,
        created_at,
        provider_cost_currency
      );
  END IF;
END $$
DELIMITER ;

CALL ensure_workflow_provider_cost_gate_index();
DROP PROCEDURE IF EXISTS ensure_workflow_provider_cost_gate_index;
