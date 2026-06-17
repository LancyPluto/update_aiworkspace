-- Align Kling Omni video fields with the current OmniVideo API.
-- Apply with: mysql --default-character-set=utf8mb4 ...
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '时长',
  f.field_type = 'slider',
  f.placeholder = NULL,
  f.options_json = JSON_OBJECT(
    'slider', JSON_OBJECT('min', 3, 'max', 15, 'step', 1),
    'defaultValue', 5,
    'unit', '秒'
  ),
  f.default_value = '5',
  f.required = 1,
  f.execution_required = 1,
  f.user_required = 1,
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key = 'duration';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '参考视频列表',
  f.field_type = 'omni_video_list',
  f.placeholder = '上传或选择参考视频',
  f.options_json = JSON_OBJECT(
    'maxCount', 4,
    'accept', 'video/*',
    'libraryEnabled', true,
    'libraryKind', 'video',
    'uiTier', 'advanced'
  ),
  f.default_value = NULL,
  f.required = 0,
  f.execution_required = 0,
  f.user_required = 0,
  f.status = 'ACTIVE',
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.field_key = 'videoList';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '主体参考列表',
  f.field_type = 'subject_element_list',
  f.placeholder = '添加主体参考',
  f.options_json = JSON_OBJECT(
    'maxCount', 7,
    'libraryEnabled', true,
    'uiTier', 'advanced'
  ),
  f.default_value = NULL,
  f.required = 0,
  f.execution_required = 0,
  f.user_required = 0,
  f.status = 'ACTIVE',
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.field_key = 'elementList';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '质量档位',
  f.field_type = 'radio',
  f.options_json = JSON_ARRAY(
    JSON_OBJECT('label', '标准（std）', 'value', 'std'),
    JSON_OBJECT('label', '高质量（pro）', 'value', 'pro')
  ),
  f.default_value = 'std',
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key = 'mode';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '音频',
  f.field_type = 'radio',
  f.options_json = JSON_ARRAY(
    JSON_OBJECT('label', '关闭', 'value', 'off'),
    JSON_OBJECT('label', '开启', 'value', 'on')
  ),
  f.default_value = 'off',
  f.required = 0,
  f.execution_required = 0,
  f.user_required = 0,
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key = 'sound';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '多镜头',
  f.field_type = 'radio',
  f.options_json = JSON_ARRAY(
    JSON_OBJECT('label', '关闭', 'value', 'false'),
    JSON_OBJECT('label', '开启', 'value', 'true')
  ),
  f.default_value = 'false',
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key = 'multiShot';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '分辨率',
  f.field_type = 'radio',
  f.options_json = JSON_ARRAY(
    JSON_OBJECT('label', '720P', 'value', '720P'),
    JSON_OBJECT('label', '1080P', 'value', '1080P')
  ),
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key = 'resolution';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = '画面比例',
  f.field_type = 'aspect_ratio',
  f.options_json = JSON_ARRAY(
    JSON_OBJECT('label', '16:9', 'value', '16:9'),
    JSON_OBJECT('label', '9:16', 'value', '9:16'),
    JSON_OBJECT('label', '1:1', 'value', '1:1')
  ),
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key = 'aspectRatio';

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.field_name = CASE f.field_key
    WHEN 'prompt' THEN '画面描述'
    WHEN 'imageList' THEN '参考图片列表'
    WHEN 'shotType' THEN '分镜类型'
    WHEN 'multiPrompt' THEN '分镜提示词'
    WHEN 'negativePrompt' THEN '反向提示词'
    ELSE f.field_name
  END,
  f.placeholder = CASE f.field_key
    WHEN 'prompt' THEN '描述主体、场景、镜头语言和细节'
    WHEN 'imageList' THEN '选择参考图片'
    WHEN 'shotType' THEN NULL
    WHEN 'multiPrompt' THEN '多镜头时的分镜描述'
    WHEN 'negativePrompt' THEN '不希望出现的元素'
    ELSE f.placeholder
  END,
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.field_key IN ('prompt', 'imageList', 'shotType', 'multiPrompt', 'negativePrompt');

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  f.status = 'INACTIVE',
  f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-omni-video'
  AND s.status = 'ACTIVE'
  AND f.field_key IN ('referType', 'refer_type');
