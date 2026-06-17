-- Split model execution routes from vendor account credentials.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

SET @current_schema = DATABASE();
SET @has_execution_task = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @current_schema
    AND TABLE_NAME = 'agent_model_configs'
    AND COLUMN_NAME = 'execution_task'
);
SET @ddl = IF(
  @has_execution_task = 0,
  'ALTER TABLE agent_model_configs ADD COLUMN execution_task VARCHAR(64) NULL AFTER extra_auth_json',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_execution_options_json = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @current_schema
    AND TABLE_NAME = 'agent_model_configs'
    AND COLUMN_NAME = 'execution_options_json'
);
SET @ddl = IF(
  @has_execution_options_json = 0,
  'ALTER TABLE agent_model_configs ADD COLUMN execution_options_json TEXT NULL AFTER execution_task',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE agent_model_configs
SET execution_task = CASE
  WHEN config_code = 'kling-gateway-omni-video' THEN 'omni_video'
  WHEN config_code = 'kling-gateway-omni-image' THEN 'omni_image'
  WHEN config_code LIKE '%multi-image%' OR config_code LIKE '%multi_image%' THEN 'multi_image2video'
  WHEN config_code LIKE '%motion%' THEN 'motion_control'
  WHEN config_code LIKE '%image-to-video%' OR config_code LIKE '%image2video%' THEN 'image2video'
  WHEN provider = 'kling_video' AND capabilities LIKE '%IMAGE_GENERATION%' THEN 'image_generation'
  WHEN provider = 'kling_video' AND capabilities LIKE '%VIDEO_GENERATION%' THEN 'text2video'
  ELSE execution_task
END
WHERE is_deleted = 0
  AND provider = 'kling_video'
  AND (execution_task IS NULL OR execution_task = '');

UPDATE agent_model_configs
SET execution_task = LOWER(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(extra_auth_json, '$.apiTask')), '-', '_'))
WHERE is_deleted = 0
  AND (execution_task IS NULL OR execution_task = '')
  AND extra_auth_json IS NOT NULL
  AND JSON_VALID(extra_auth_json)
  AND JSON_EXTRACT(extra_auth_json, '$.apiTask') IS NOT NULL;

UPDATE agent_model_configs
SET execution_options_json = JSON_OBJECT(
  'createPath', JSON_UNQUOTE(JSON_EXTRACT(extra_auth_json, '$.createPath')),
  'resultPath', JSON_UNQUOTE(JSON_EXTRACT(extra_auth_json, '$.resultPath'))
)
WHERE is_deleted = 0
  AND (execution_options_json IS NULL OR execution_options_json = '')
  AND extra_auth_json IS NOT NULL
  AND JSON_VALID(extra_auth_json)
  AND JSON_EXTRACT(extra_auth_json, '$.createPath') IS NOT NULL
  AND JSON_EXTRACT(extra_auth_json, '$.resultPath') IS NOT NULL;

UPDATE agent_model_configs
SET execution_task = 'omni_video'
WHERE is_deleted = 0
  AND config_code = 'kling-gateway-omni-video'
  AND model_name = 'kling-v3-omni';
