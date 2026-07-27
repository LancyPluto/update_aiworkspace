SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- OFOX documents n=1..10. Keep the tool slider and the bound model contract
-- aligned with that real upstream range.
UPDATE tool_field_schema_items item
JOIN tool_field_schemas schema_row ON schema_row.id = item.schema_id
JOIN ai_tools tool ON tool.id = schema_row.tool_id
SET item.options_json = JSON_SET(
      COALESCE(item.options_json, JSON_OBJECT()),
      '$.slider', JSON_OBJECT('min', 1, 'max', 10, 'step', 1),
      '$.defaultValue', 1
    ),
    item.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'gpt_image2'
  AND schema_row.status = 'ACTIVE'
  AND LOWER(item.field_key) = 'count'
  AND LOWER(item.field_type) = 'slider';

UPDATE agent_model_configs model
JOIN tool_model_bindings binding ON binding.model_config_id = model.id
JOIN ai_tools tool ON tool.id = binding.tool_id
SET model.request_schema_json = JSON_SET(
      CAST(model.request_schema_json AS JSON),
      '$.fields[5].max', 10,
      '$.fields[5].step', 1
    ),
    model.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'gpt_image2'
  AND model.request_schema_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(model.request_schema_json, '$.fields[5].key')) = 'count';
