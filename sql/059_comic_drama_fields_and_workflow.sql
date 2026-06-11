-- Comic drama interactive workflow form fields (tool_field_schema_items v1)
DELETE i FROM tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT
  s.id, 'storyTheme', '漫剧主题', 'text', '例如：穿越后我靠 AI 开店逆袭', NULL,
  JSON_OBJECT('maxLength', 120), 1, 0, 0, NULL, 'default', 'LOW', 1, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT
  s.id, 'genre', '题材类型', 'select', '选择漫剧题材',
  JSON_ARRAY('都市逆袭', '甜宠恋爱', '悬疑反转', '科幻脑洞'), NULL, 0, 0, 0, NULL, 'default', 'LOW', 2, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT
  s.id, 'plotOutline', '剧情梗概（可选）', 'textarea', '留空则由大模型自动生成剧本与分镜', NULL, NULL,
  0, 0, 0, NULL, 'default', 'LOW', 3, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT
  s.id, 'visualStyle', '画风风格', 'select', '选择画面风格',
  JSON_ARRAY('电影感写实', '国漫厚涂', '日漫赛璐璐', 'Q 版轻喜剧'), NULL, 0, 0, 0, NULL, 'default', 'LOW', 4, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT
  s.id, 'aspectRatio', '画面比例', 'select', '选择画幅',
  JSON_ARRAY('16:9', '9:16', '1:1'), NULL, 0, 0, 0, NULL, 'default', 'LOW', 5, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT
  s.id, 'episodeLength', '单集时长', 'select', '选择目标时长',
  JSON_ARRAY('30s', '60s', '90s'), NULL, 0, 0, 0, NULL, 'default', 'LOW', 6, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';
