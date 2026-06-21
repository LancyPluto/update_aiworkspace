SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_ddl_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @stmt = p_ddl_sql;
        PREPARE prepared_stmt FROM @stmt;
        EXECUTE prepared_stmt;
        DEALLOCATE PREPARE prepared_stmt;
    END IF;
END $$

DELIMITER ;

CALL add_column_if_missing(
    'agent_tool_preferences',
    'disabled',
    'ALTER TABLE agent_tool_preferences ADD COLUMN disabled TINYINT NOT NULL DEFAULT 0 AFTER auto_call_enabled'
);

DROP PROCEDURE IF EXISTS add_column_if_missing;
