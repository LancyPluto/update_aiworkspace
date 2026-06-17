-- Align Kling motion control with the official /v1/videos/motion-control API.
-- Apply with: mysql --default-character-set=utf8mb4 ...
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE agent_model_configs
SET execution_task = 'motion_control',
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND config_code = 'kling-gateway-motion-control';

UPDATE ai_tools
SET status = 'ONLINE',
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND tool_code = 'kling-motion-control';

INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json,
  validation_json, required, execution_required, user_required, default_value,
  agent_fill_strategy, risk_level, sort_order, status
)
SELECT s.id, seed.field_key, seed.field_name, seed.field_type, seed.placeholder, seed.options_json,
       NULL, seed.required, seed.execution_required, seed.user_required, seed.default_value,
       'default', 'LOW', seed.sort_order, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
JOIN (
  SELECT 'imageUrl' AS field_key, '人物图片' AS field_name, 'image_upload' AS field_type,
         '上传角色参考图，人物比例尽量与动作视频一致' AS placeholder,
         JSON_OBJECT(
           'uiGroup', 'core',
           'uiGroupLabel', '核心输入',
           'uiTier', 'all',
           'uiRole', 'character_image',
           'uiOrder', 1,
           'layoutHint', 'paired_media',
           'accept', 'image/jpeg,image/png,.jpg,.jpeg,.png',
           'maxSizeMb', 10,
           'helpText', '上传角色参考图，人物比例尽量与动作视频一致'
         ) AS options_json,
         1 AS required, 1 AS execution_required, 1 AS user_required, NULL AS default_value, 1 AS sort_order
  UNION ALL
  SELECT 'videoUrl', '动作视频', 'video_upload',
         '上传公网可访问的 MP4/MOV 动作视频；3 秒起，不超过 100MB',
         JSON_OBJECT(
           'uiGroup', 'core',
           'uiGroupLabel', '核心输入',
           'uiTier', 'all',
           'uiRole', 'motion_video',
           'uiOrder', 2,
           'layoutHint', 'paired_media',
           'accept', '.mp4,.mov,video/mp4,video/quicktime',
           'maxSizeMb', 100,
           'requiresPublicUrl', true,
           'minDuration', 3,
           'durationByOrientation', JSON_OBJECT('image', 10, 'video', 30),
           'helpText', 'MP4/MOV，公网可访问，3 秒起，不超过 100MB'
         ),
         1, 1, 1, NULL, 2
  UNION ALL
  SELECT 'elementList', '主体参考', 'subject_element_list',
         '可选：选择 1 个已就绪主体',
         JSON_OBJECT(
           'uiGroup', 'subject',
           'uiGroupLabel', '主体参考',
           'uiTier', 'all',
           'uiRole', 'subject_element',
           'uiOrder', 3,
           'layoutHint', 'full_width',
           'maxCount', 1,
           'maxItems', 1,
           'libraryEnabled', true,
           'allowedModes', JSON_ARRAY('library_ref', 'element_id'),
           'forceCharacterOrientation', 'video',
           'helpText', '可选：使用主体库保持角色一致性；引用主体时角色朝向会锁定为跟随视频'
         ),
         0, 0, 0, NULL, 3
  UNION ALL
  SELECT 'prompt', '补充描述', 'textarea',
         '可选：补充角色服装、场景或镜头效果',
         JSON_OBJECT(
           'uiGroup', 'settings',
           'uiGroupLabel', '常用设置',
           'uiTier', 'all',
           'uiRole', 'motion_prompt',
           'uiOrder', 4,
           'layoutHint', 'full_width',
           'maxLength', 2500,
           'helpText', '可通过描述补充服装、场景、镜头或想保留的细节'
         ),
         0, 0, 0, NULL, 4
  UNION ALL
  SELECT 'characterOrientation', '角色朝向', 'select',
         NULL,
         JSON_OBJECT(
           'uiGroup', 'settings',
           'uiGroupLabel', '常用设置',
           'uiTier', 'all',
           'uiRole', 'character_orientation',
           'uiOrder', 5,
           'options', JSON_ARRAY(
             JSON_OBJECT('label', '跟随视频', 'value', 'video'),
             JSON_OBJECT('label', '跟随图片', 'value', 'image')
           ),
           'helpText', '跟随图片最长 10 秒；跟随视频最长 30 秒。引用主体时只能跟随视频'
         ),
         1, 1, 1, 'video', 5
  UNION ALL
  SELECT 'mode', '质量档位', 'radio',
         NULL,
         JSON_OBJECT(
           'uiGroup', 'settings',
           'uiGroupLabel', '常用设置',
           'uiTier', 'all',
           'uiRole', 'quality_mode',
           'uiOrder', 6,
           'options', JSON_ARRAY(
             JSON_OBJECT('label', '标准（std）', 'value', 'std'),
             JSON_OBJECT('label', '高质量（pro）', 'value', 'pro')
           ),
           'defaultValue', 'std'
         ),
         1, 1, 1, 'std', 6
  UNION ALL
  SELECT 'keepOriginalSound', '保留原声', 'radio',
         NULL,
         JSON_OBJECT(
           'uiGroup', 'settings',
           'uiGroupLabel', '常用设置',
           'uiTier', 'all',
           'uiRole', 'keep_original_sound',
           'uiOrder', 7,
           'options', JSON_ARRAY(
             JSON_OBJECT('label', '是', 'value', 'yes'),
             JSON_OBJECT('label', '否', 'value', 'no')
           ),
           'defaultValue', 'yes'
         ),
         0, 0, 0, 'yes', 7
  UNION ALL
  SELECT 'staticMask', '静态遮罩', 'image_upload',
         '高级实验项：当前动作控制接口暂不提交遮罩参数',
         JSON_OBJECT(
           'uiGroup', 'advanced',
           'uiGroupLabel', '高级参数',
           'uiTier', 'advanced',
           'uiRole', 'static_mask',
           'uiOrder', 8,
           'layoutHint', 'paired_media',
           'submitPolicy', 'ui_only',
           'accept', 'image/jpeg,image/png,.jpg,.jpeg,.png',
           'helpText', '当前动作控制接口暂不提交遮罩参数，确认官方字段后可改为 submit'
         ),
         0, 0, 0, NULL, 8
  UNION ALL
  SELECT 'dynamicMasks', '动态遮罩', 'textarea',
         '高级实验项：当前动作控制接口暂不提交遮罩参数',
         JSON_OBJECT(
           'uiGroup', 'advanced',
           'uiGroupLabel', '高级参数',
           'uiTier', 'advanced',
           'uiRole', 'dynamic_mask',
           'uiOrder', 9,
           'layoutHint', 'paired_media',
           'submitPolicy', 'ui_only',
           'helpText', '当前动作控制接口暂不提交遮罩参数，确认官方字段后可改为 submit'
         ),
         0, 0, 0, NULL, 9
) seed
WHERE t.tool_code = 'kling-motion-control'
  AND s.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
  field_name = VALUES(field_name),
  field_type = VALUES(field_type),
  placeholder = VALUES(placeholder),
  options_json = VALUES(options_json),
  required = VALUES(required),
  execution_required = VALUES(execution_required),
  user_required = VALUES(user_required),
  default_value = VALUES(default_value),
  sort_order = VALUES(sort_order),
  status = 'ACTIVE',
  updated_at = CURRENT_TIMESTAMP;

UPDATE tool_field_schema_items f
JOIN tool_field_schemas s ON s.id = f.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET f.status = 'INACTIVE',
    f.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'kling-motion-control'
  AND s.status = 'ACTIVE'
  AND f.field_key IN ('aspectRatio', 'duration', 'sound', 'negativePrompt', 'imageList', 'videoList', 'multiShot', 'shotType', 'multiPrompt');
