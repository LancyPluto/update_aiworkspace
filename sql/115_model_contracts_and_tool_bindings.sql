SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ensure_model_contract_columns;

DELIMITER $$
CREATE PROCEDURE ensure_model_contract_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'request_schema_json'
  ) THEN
    ALTER TABLE agent_model_configs ADD COLUMN request_schema_json MEDIUMTEXT NULL AFTER execution_options_json;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'request_mapping_json'
  ) THEN
    ALTER TABLE agent_model_configs ADD COLUMN request_mapping_json MEDIUMTEXT NULL AFTER request_schema_json;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'response_mapping_json'
  ) THEN
    ALTER TABLE agent_model_configs ADD COLUMN response_mapping_json MEDIUMTEXT NULL AFTER request_mapping_json;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'api_contract_version'
  ) THEN
    ALTER TABLE agent_model_configs ADD COLUMN api_contract_version VARCHAR(64) NULL AFTER response_mapping_json;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'contract_status'
  ) THEN
    ALTER TABLE agent_model_configs
      ADD COLUMN contract_status VARCHAR(32) NOT NULL DEFAULT 'DOCS_PENDING' AFTER api_contract_version;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_model_configs'
      AND column_name = 'contract_verified_at'
  ) THEN
    ALTER TABLE agent_model_configs ADD COLUMN contract_verified_at DATETIME NULL AFTER contract_status;
  END IF;
END $$
DELIMITER ;

CALL ensure_model_contract_columns();
DROP PROCEDURE IF EXISTS ensure_model_contract_columns;

CREATE TABLE IF NOT EXISTS tool_model_bindings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  model_config_id BIGINT NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_model_binding(tool_id, model_config_id),
  KEY idx_tool_model_binding_default(tool_id, is_default, sort_order, id),
  KEY idx_tool_model_binding_model(model_config_id, tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO tool_model_bindings(tool_id, model_config_id, is_default, sort_order)
SELECT id, model_config_id, 1, 0
FROM ai_tools
WHERE is_deleted = 0
  AND model_config_id IS NOT NULL;
