SET NAMES utf8mb4;

-- TOKEN_PER_M models need an explicit pre-execution usage estimate. NULL means
-- no reliable estimate is configured, so publishing a paid workflow fails closed.
DROP PROCEDURE IF EXISTS ensure_workflow_auto_pricing_columns;

DELIMITER $$
CREATE PROCEDURE ensure_workflow_auto_pricing_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'pricing_margins'
      AND column_name = 'token_estimate_input_tokens'
  ) THEN
    ALTER TABLE pricing_margins
      ADD COLUMN token_estimate_input_tokens INT NULL AFTER image_estimate_output_tokens;
  ELSEIF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'pricing_margins'
      AND column_name = 'token_estimate_input_tokens'
      AND data_type = 'int'
      AND is_nullable = 'YES'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'pricing_margins.token_estimate_input_tokens has an incompatible definition';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'pricing_margins'
      AND column_name = 'token_estimate_output_tokens'
  ) THEN
    ALTER TABLE pricing_margins
      ADD COLUMN token_estimate_output_tokens INT NULL AFTER token_estimate_input_tokens;
  ELSEIF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'pricing_margins'
      AND column_name = 'token_estimate_output_tokens'
      AND data_type = 'int'
      AND is_nullable = 'YES'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'pricing_margins.token_estimate_output_tokens has an incompatible definition';
  END IF;
END $$
DELIMITER ;

CALL ensure_workflow_auto_pricing_columns();
DROP PROCEDURE IF EXISTS ensure_workflow_auto_pricing_columns;
