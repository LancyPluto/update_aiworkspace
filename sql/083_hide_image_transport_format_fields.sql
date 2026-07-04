-- Hide upstream transport knobs from user-facing image generation forms.
-- `responseFormat` selects API response transport (URL/base64) and should never be user input.
UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET f.status = 'INACTIVE',
    f.updated_at = CURRENT_TIMESTAMP
WHERE s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key IN ('responseFormat', 'response_format');

-- `outputFormat` is not supported by GPT Image 2 and similar gateway models.
-- Keep dedicated image utility templates untouched; only remove it from generic image generators.
UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET f.status = 'INACTIVE',
    f.updated_at = CURRENT_TIMESTAMP
WHERE s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key IN ('outputFormat', 'output_format')
  AND (
    t.tool_code IN ('volcengine-image', 'gpt_image_text_to_image')
    OR LOWER(t.tool_code) LIKE 'gpt_image%'
    OR LOWER(t.tool_code) LIKE 'openai_image%'
  );
