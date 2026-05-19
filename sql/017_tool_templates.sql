SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tool_templates (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_code VARCHAR(128) NOT NULL UNIQUE,
  template_name VARCHAR(128) NOT NULL,
  tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  execution_handler VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  config_note TEXT,
  default_system_prompt TEXT,
  default_user_prompt_template MEDIUMTEXT,
  default_output_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  handler_config_json JSON,
  suggested_model_config_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  is_system TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tool_templates_status (status),
  KEY idx_tool_templates_handler (execution_handler)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_template_fields (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  field_type VARCHAR(32) NOT NULL,
  placeholder VARCHAR(255),
  options_json JSON,
  validation_json JSON,
  required TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_template_field (template_id, field_key),
  KEY idx_template_fields_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ai_tools.template_id / execution_handler 见 013_ai_tools_modality_columns.sql；Java bootstrap 仍会做幂等补齐
