SET NAMES utf8mb4;

-- The deployment pipeline runs SQL before the application DataInitializer.
-- Older databases may already have this column; fresh databases do not.
DROP PROCEDURE IF EXISTS add_agent_model_extra_auth_if_missing;

DELIMITER $$
CREATE PROCEDURE add_agent_model_extra_auth_if_missing()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'extra_auth_json'
  ) THEN
    ALTER TABLE agent_model_configs
      ADD COLUMN extra_auth_json TEXT NULL AFTER api_key;
  END IF;
END $$
DELIMITER ;

CALL add_agent_model_extra_auth_if_missing();
DROP PROCEDURE IF EXISTS add_agent_model_extra_auth_if_missing;
