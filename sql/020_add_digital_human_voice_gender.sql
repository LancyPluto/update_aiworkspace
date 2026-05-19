SET NAMES utf8mb4;

INSERT INTO tool_field_schema_items (
  schema_id,
  field_key,
  field_name,
  field_type,
  placeholder,
  options_json,
  validation_json,
  required,
  sort_order,
  status
)
SELECT
  s.id,
  'presenterGender',
  '讲述人性别/音色',
  'select',
  '选择与数字人形象一致的声音；不确定时选自动',
  JSON_ARRAY('自动匹配', '女声', '男声'),
  NULL,
  0,
  4,
  'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'digital_human_agent'
  AND s.schema_version = 'v1'
  AND NOT EXISTS (
    SELECT 1
    FROM tool_field_schema_items i
    WHERE i.schema_id = s.id
      AND i.field_key = 'presenterGender'
  );

UPDATE tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET i.sort_order = i.sort_order + 1
WHERE t.tool_code = 'digital_human_agent'
  AND s.schema_version = 'v1'
  AND i.field_key IN (
    'scene',
    'aspectRatio',
    'duration',
    'resolution',
    'brandName',
    'referenceImageUrl',
    'visualRequirements',
    'negativePrompt'
  );
