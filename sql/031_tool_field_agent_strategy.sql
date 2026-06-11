SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL add_column_if_missing('tool_field_schema_items', 'execution_required', '`execution_required` TINYINT NOT NULL DEFAULT 0');
CALL add_column_if_missing('tool_field_schema_items', 'user_required', '`user_required` TINYINT NOT NULL DEFAULT 0');
CALL add_column_if_missing('tool_field_schema_items', 'default_value', '`default_value` VARCHAR(512) NULL');
CALL add_column_if_missing('tool_field_schema_items', 'agent_fill_strategy', '`agent_fill_strategy` VARCHAR(32) NOT NULL DEFAULT ''default''');
CALL add_column_if_missing('tool_field_schema_items', 'risk_level', '`risk_level` VARCHAR(16) NOT NULL DEFAULT ''LOW''');

UPDATE tool_field_schema_items
SET execution_required = required,
    user_required = required,
    agent_fill_strategy = CASE WHEN required = 1 THEN 'ask_user' ELSE 'default' END,
    risk_level = 'LOW'
WHERE agent_fill_strategy IS NULL OR agent_fill_strategy = '';

DROP PROCEDURE IF EXISTS add_column_if_missing;
