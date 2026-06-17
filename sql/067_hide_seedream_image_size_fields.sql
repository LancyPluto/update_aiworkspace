-- Keep Seedream creator forms ratio-only: the worker maps aspectRatio/imageRatio
-- to provider-legal pixel sizes, so exposing imageSize creates conflicting inputs.
UPDATE tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
LEFT JOIN agent_model_configs m ON m.id = t.model_config_id
SET i.status = 'INACTIVE',
    i.updated_at = NOW()
WHERE s.status = 'ACTIVE'
  AND i.status = 'ACTIVE'
  AND i.field_key IN ('imageSize', 'image_size', 'size')
  AND (
    LOWER(COALESCE(t.tool_code, '')) LIKE '%seedream%'
    OR LOWER(COALESCE(m.model_name, '')) LIKE '%seedream%'
    OR LOWER(COALESCE(m.provider, '')) = 'volcengine_images'
  );
