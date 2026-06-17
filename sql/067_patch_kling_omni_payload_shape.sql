-- Align Kling Omni form fields with the documented omni-video request body.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET f.status = 'INACTIVE',
    f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND t.is_deleted = 0
  AND s.status = 'ACTIVE'
  AND f.field_key = 'resolution';
