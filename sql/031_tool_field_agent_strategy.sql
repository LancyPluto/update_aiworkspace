ALTER TABLE tool_field_schema_items
  ADD COLUMN execution_required TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN user_required TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN default_value VARCHAR(512) NULL,
  ADD COLUMN agent_fill_strategy VARCHAR(32) NOT NULL DEFAULT 'default',
  ADD COLUMN risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW';

UPDATE tool_field_schema_items
SET execution_required = required,
    user_required = required,
    agent_fill_strategy = CASE WHEN required = 1 THEN 'ask_user' ELSE 'default' END,
    risk_level = 'LOW'
WHERE agent_fill_strategy IS NULL OR agent_fill_strategy = '';
