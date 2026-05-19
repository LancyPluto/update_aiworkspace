SET NAMES utf8mb4;

-- Columns required by agent/video seed scripts (014+) and tool templates.
-- Kept idempotent for DBs where Java bootstrap already added them.

SET @db = DATABASE();

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_tools' AND COLUMN_NAME = 'tool_type') = 0,
  'ALTER TABLE ai_tools ADD COLUMN tool_type VARCHAR(32) NOT NULL DEFAULT ''TEXT_GENERATION''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_tools' AND COLUMN_NAME = 'input_modality') = 0,
  'ALTER TABLE ai_tools ADD COLUMN input_modality VARCHAR(32) NOT NULL DEFAULT ''TEXT''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_tools' AND COLUMN_NAME = 'output_modality') = 0,
  'ALTER TABLE ai_tools ADD COLUMN output_modality VARCHAR(32) NOT NULL DEFAULT ''TEXT''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_tools' AND COLUMN_NAME = 'config_note') = 0,
  'ALTER TABLE ai_tools ADD COLUMN config_note TEXT NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_tools' AND COLUMN_NAME = 'template_id') = 0,
  'ALTER TABLE ai_tools ADD COLUMN template_id BIGINT NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_tools' AND COLUMN_NAME = 'execution_handler') = 0,
  'ALTER TABLE ai_tools ADD COLUMN execution_handler VARCHAR(32) NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
