SET NAMES utf8mb4;

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, sort_order, status
)
SELECT
  s.id,
  seed.field_key,
  seed.field_name,
  seed.field_type,
  seed.placeholder,
  seed.options_json,
  seed.validation_json,
  seed.required,
  seed.sort_order,
  'ACTIVE'
FROM (
  SELECT 'scriptRevision' AS field_key, '剧本修订意见' AS field_name, 'textarea' AS field_type,
    '在工作台确认剧本后填写修改意见，留空则继续生成' AS placeholder,
    NULL AS options_json, JSON_OBJECT('maxLength', 800) AS validation_json, 0 AS required, 13 AS sort_order
  UNION ALL
  SELECT 'visualRevision', '画面修订意见', 'textarea',
    '对关键帧/分镜画面提出修改要求',
    NULL, JSON_OBJECT('maxLength', 800), 0, 14
) seed
JOIN tool_field_schemas s ON s.schema_version = 'v1'
JOIN ai_tools t ON t.id = s.tool_id AND t.tool_code = 'ai_comic_drama_agent'
WHERE NOT EXISTS (
  SELECT 1 FROM tool_field_schema_items i
  WHERE i.schema_id = s.id AND i.field_key = seed.field_key
);
