SET NAMES utf8mb4;

-- Persist the immutable accounting facts reported by a successful worker so an
-- over-estimate shortfall can be settled after recharge without replaying it.
DROP PROCEDURE IF EXISTS ensure_workflow_settlement_payload;

DELIMITER $$
CREATE PROCEDURE ensure_workflow_settlement_payload()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_step_charges'
      AND column_name = 'settlement_payload_json'
  ) THEN
    ALTER TABLE workflow_step_charges
      ADD COLUMN settlement_payload_json MEDIUMTEXT NULL AFTER billing_usage_id;
  ELSEIF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_step_charges'
      AND column_name = 'settlement_payload_json'
      AND data_type IN ('tinytext', 'text')
  ) THEN
    ALTER TABLE workflow_step_charges
      MODIFY COLUMN settlement_payload_json MEDIUMTEXT NULL;
  ELSEIF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_step_charges'
      AND column_name = 'settlement_payload_json'
      AND data_type IN ('mediumtext', 'longtext', 'json')
      AND is_nullable = 'YES'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'workflow_step_charges.settlement_payload_json has an incompatible definition';
  END IF;
END $$
DELIMITER ;

CALL ensure_workflow_settlement_payload();
DROP PROCEDURE IF EXISTS ensure_workflow_settlement_payload;
