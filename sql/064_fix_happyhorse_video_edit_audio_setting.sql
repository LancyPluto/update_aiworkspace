SET NAMES utf8mb4;

-- Align happyhorse_video_edit audioSetting with DashScope API (auto | origin only).

UPDATE tool_field_definitions f
JOIN tool_field_schemas s ON f.schema_id = s.id AND s.schema_version = 'v1'
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.options_json = JSON_ARRAY(
    JSON_OBJECT('label', '模型自动控制', 'value', 'auto'),
    JSON_OBJECT('label', '保留原音频', 'value', 'origin')
  ),
  f.default_value = 'auto',
  f.placeholder = 'API 仅支持 auto（模型决定）或 origin（保留原声）',
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'happyhorse_video_edit'
  AND f.field_key = 'audioSetting';
