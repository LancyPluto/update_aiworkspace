SET NAMES utf8mb4;

INSERT INTO tool_categories (category_code, category_name, sort_order, status)
VALUES ('video_generation', '视频生成', 3, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  category_name = VALUES(category_name),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO agent_model_configs (
  display_name,
  config_code,
  provider,
  model_name,
  base_url,
  api_key,
  console_url,
  docs_url,
  timeout_seconds,
  billing_unit,
  unit_price,
  capabilities,
  enabled,
  agent_enabled,
  is_default
)
VALUES
(
  'HappyHorse 文生视频',
  'happyhorse_t2v',
  'bailian_happyhorse',
  'happyhorse-1.0-t2v',
  'https://dashscope.aliyuncs.com',
  'replace-with-bailian-api-key',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3029820',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3029820',
  3600,
  'PER_SECOND',
  0.00000000,
  JSON_ARRAY('VIDEO_GENERATION'),
  1,
  0,
  0
),
(
  'HappyHorse 图生视频-基于首帧',
  'happyhorse_i2v',
  'bailian_happyhorse',
  'happyhorse-1.0-i2v',
  'https://dashscope.aliyuncs.com',
  'replace-with-bailian-api-key',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3029821',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3029821',
  3600,
  'PER_SECOND',
  0.00000000,
  JSON_ARRAY('VIDEO_GENERATION'),
  1,
  0,
  0
),
(
  'HappyHorse 参考生视频',
  'happyhorse_r2v',
  'bailian_happyhorse',
  'happyhorse-1.0-r2v',
  'https://dashscope.aliyuncs.com',
  'replace-with-bailian-api-key',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3030778',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3030778',
  3600,
  'PER_SECOND',
  0.00000000,
  JSON_ARRAY('VIDEO_GENERATION'),
  1,
  0,
  0
),
(
  'HappyHorse 视频编辑',
  'happyhorse_video_edit',
  'bailian_happyhorse',
  'happyhorse-1.0-video-edit',
  'https://dashscope.aliyuncs.com',
  'replace-with-bailian-api-key',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3030779',
  'https://bailian.console.aliyun.com/cn-beijing#/api/?type=model&url=3030779',
  3600,
  'PER_SECOND',
  0.00000000,
  JSON_ARRAY('VIDEO_GENERATION'),
  1,
  0,
  0
)
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  provider = VALUES(provider),
  model_name = VALUES(model_name),
  base_url = VALUES(base_url),
  api_key = IF(api_key IS NULL OR api_key = '' OR api_key LIKE 'replace-with-%', VALUES(api_key), api_key),
  console_url = VALUES(console_url),
  docs_url = VALUES(docs_url),
  timeout_seconds = VALUES(timeout_seconds),
  billing_unit = VALUES(billing_unit),
  unit_price = VALUES(unit_price),
  capabilities = VALUES(capabilities),
  enabled = 1,
  updated_at = CURRENT_TIMESTAMP;

INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost,
  model_config_id,
  tool_type,
  execution_handler,
  input_modality,
  output_modality,
  config_note
)
SELECT seed.tool_code, seed.tool_name, c.id, seed.description, 'ONLINE', seed.estimated_credit_cost, m.id,
       'VIDEO_GENERATION', 'VIDEO_GENERATION', seed.input_modality, 'VIDEO', seed.config_note
FROM (
  SELECT 'happyhorse_text_to_video' AS tool_code, 'HappyHorse-文生视频' AS tool_name,
         '通过文本提示词生成视频，适合创意短片、产品镜头和场景概念视频。' AS description,
         120 AS estimated_credit_cost, 'happyhorse_t2v' AS config_code, 'TEXT' AS input_modality,
         '使用百炼 HappyHorse 文生视频模型，管理员在统一 API 设置填写百炼 API Key 后即可执行。' AS config_note
  UNION ALL
  SELECT 'happyhorse_image_to_video', 'HappyHorse-图生视频-基于首帧',
         '基于首帧图片和文本提示词生成延展视频。', 120, 'happyhorse_i2v', 'MULTIMODAL',
         '使用百炼 HappyHorse 图生视频模型，首帧图片字段会作为 first_frame media 传入。'
  UNION ALL
  SELECT 'happyhorse_reference_to_video', 'HappyHorse-参考生视频',
         '根据多张参考图和提示词生成视频，适合角色、产品或风格参考驱动的视频生成。', 180, 'happyhorse_r2v', 'MULTIMODAL',
         'referenceImages 使用 multi_image 控件，Worker 同时兼容旧 referenceImageUrls 参数。'
  UNION ALL
  SELECT 'happyhorse_video_edit', 'HappyHorse-视频编辑',
         '基于源视频、提示词和可选参考图进行视频编辑。', 180, 'happyhorse_video_edit', 'MULTIMODAL',
         'sourceVideo 作为源视频输入，referenceImages 可追加多张图片参考。'
) seed
JOIN tool_categories c ON c.category_code = 'video_generation'
JOIN agent_model_configs m ON m.config_code = seed.config_code
WHERE NOT EXISTS (SELECT 1 FROM ai_tools t WHERE t.tool_code = seed.tool_code);

UPDATE ai_tools t
JOIN (
  SELECT 'happyhorse_text_to_video' AS tool_code, 'HappyHorse-文生视频' AS tool_name,
         '通过文本提示词生成视频，适合创意短片、产品镜头和场景概念视频。' AS description,
         120 AS estimated_credit_cost, 'happyhorse_t2v' AS config_code, 'TEXT' AS input_modality
  UNION ALL SELECT 'happyhorse_image_to_video', 'HappyHorse-图生视频-基于首帧', '基于首帧图片和文本提示词生成延展视频。', 120, 'happyhorse_i2v', 'MULTIMODAL'
  UNION ALL SELECT 'happyhorse_reference_to_video', 'HappyHorse-参考生视频', '根据多张参考图和提示词生成视频，适合角色、产品或风格参考驱动的视频生成。', 180, 'happyhorse_r2v', 'MULTIMODAL'
  UNION ALL SELECT 'happyhorse_video_edit', 'HappyHorse-视频编辑', '基于源视频、提示词和可选参考图进行视频编辑。', 180, 'happyhorse_video_edit', 'MULTIMODAL'
) seed ON seed.tool_code = t.tool_code
JOIN agent_model_configs m ON m.config_code = seed.config_code
SET
  t.tool_name = seed.tool_name,
  t.description = seed.description,
  t.status = 'ONLINE',
  t.estimated_credit_cost = seed.estimated_credit_cost,
  t.model_config_id = m.id,
  t.tool_type = 'VIDEO_GENERATION',
  t.execution_handler = 'VIDEO_GENERATION',
  t.input_modality = seed.input_modality,
  t.output_modality = 'VIDEO',
  t.updated_at = CURRENT_TIMESTAMP;

INSERT INTO tool_field_schemas (tool_id, schema_version, status)
SELECT t.id, 'v1', 'ACTIVE'
FROM ai_tools t
WHERE t.tool_code IN ('happyhorse_text_to_video', 'happyhorse_image_to_video', 'happyhorse_reference_to_video', 'happyhorse_video_edit')
  AND NOT EXISTS (
    SELECT 1 FROM tool_field_schemas s WHERE s.tool_id = t.id AND s.schema_version = 'v1'
  );

INSERT INTO tool_field_schema_items (
  schema_id,
  field_key,
  field_name,
  field_type,
  placeholder,
  options_json,
  validation_json,
  required,
  execution_required,
  user_required,
  default_value,
  sort_order,
  status
)
SELECT seed.schema_id, seed.field_key, seed.field_name, seed.field_type, seed.placeholder, seed.options_json,
       seed.validation_json, seed.required, seed.execution_required, seed.user_required, seed.default_value,
       seed.sort_order, 'ACTIVE'
FROM (
  SELECT s.id AS schema_id, 'prompt' AS field_key, '提示词' AS field_name, 'textarea' AS field_type,
         '描述主体、场景、光线、构图和细节' AS placeholder, JSON_OBJECT('maxLength', 1200, 'core', true) AS options_json,
         JSON_OBJECT('maxLength', 1200) AS validation_json, 1 AS required, 1 AS execution_required, 1 AS user_required,
         NULL AS default_value, 1 AS sort_order
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_text_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'resolution', '清晰度', 'select', '选择输出清晰度',
         JSON_ARRAY(JSON_OBJECT('label','720P','value','720P'), JSON_OBJECT('label','1080P','value','1080P')),
         NULL, 1, 1, 1, '720P', 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_text_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'ratio', '比例', 'aspect_ratio', '选择视频画面比例',
         JSON_ARRAY(JSON_OBJECT('label','智能','value','auto'), JSON_OBJECT('label','9:16','value','9:16'), JSON_OBJECT('label','1:1','value','1:1'), JSON_OBJECT('label','4:3','value','4:3'), JSON_OBJECT('label','16:9','value','16:9')),
         NULL, 1, 1, 1, '16:9', 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_text_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'duration', '时长', 'select', '选择视频时长',
         JSON_ARRAY(JSON_OBJECT('label','3 秒','value','3'), JSON_OBJECT('label','5 秒','value','5'), JSON_OBJECT('label','8 秒','value','8'), JSON_OBJECT('label','10 秒','value','10')),
         NULL, 1, 1, 1, '5', 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_text_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'checkbox', '生成视频带水印',
         NULL, NULL, 0, 0, 0, 'false', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_text_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'seed', '随机种子', 'number', '可选，填写整数便于复现',
         NULL, NULL, 0, 0, 0, NULL, 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_text_to_video' AND s.schema_version = 'v1'

  UNION ALL
  SELECT s.id, 'firstFrameImage', '首帧图片', 'image', '上传或粘贴首帧图片 URL',
         JSON_OBJECT('accept','image/*'), NULL, 1, 1, 1, NULL, 1
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_image_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'prompt', '提示词', 'textarea', '描述首帧之后的运动、镜头和氛围',
         JSON_OBJECT('maxLength', 1200, 'core', true), JSON_OBJECT('maxLength', 1200), 1, 1, 1, NULL, 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_image_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'resolution', '清晰度', 'select', '选择输出清晰度',
         JSON_ARRAY(JSON_OBJECT('label','720P','value','720P'), JSON_OBJECT('label','1080P','value','1080P')),
         NULL, 1, 1, 1, '720P', 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_image_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'duration', '时长', 'select', '选择视频时长',
         JSON_ARRAY(JSON_OBJECT('label','3 秒','value','3'), JSON_OBJECT('label','5 秒','value','5'), JSON_OBJECT('label','8 秒','value','8'), JSON_OBJECT('label','10 秒','value','10')),
         NULL, 1, 1, 1, '5', 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_image_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'checkbox', '生成视频带水印', NULL, NULL, 0, 0, 0, 'false', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_image_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'seed', '随机种子', 'number', '可选，填写整数便于复现', NULL, NULL, 0, 0, 0, NULL, 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_image_to_video' AND s.schema_version = 'v1'

  UNION ALL
  SELECT s.id, 'prompt', '提示词', 'textarea', '描述参考图如何转成视频画面',
         JSON_OBJECT('maxLength', 1200, 'core', true), JSON_OBJECT('maxLength', 1200), 1, 1, 1, NULL, 1
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_reference_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'referenceImages', '参考图', 'multi_image', '上传或从素材库选择参考图',
         JSON_OBJECT('minCount',1,'maxCount',9,'accept','image/*','libraryEnabled',true,'libraryKind','image'),
         JSON_OBJECT('minCount',1,'maxCount',9), 1, 1, 1, NULL, 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_reference_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'resolution', '清晰度', 'select', '选择输出清晰度',
         JSON_ARRAY(JSON_OBJECT('label','720P','value','720P'), JSON_OBJECT('label','1080P','value','1080P')),
         NULL, 1, 1, 1, '720P', 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_reference_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'ratio', '比例', 'aspect_ratio', '选择视频画面比例',
         JSON_ARRAY(JSON_OBJECT('label','智能','value','auto'), JSON_OBJECT('label','9:16','value','9:16'), JSON_OBJECT('label','1:1','value','1:1'), JSON_OBJECT('label','4:3','value','4:3'), JSON_OBJECT('label','16:9','value','16:9')),
         NULL, 1, 1, 1, '16:9', 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_reference_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'duration', '时长', 'select', '选择视频时长',
         JSON_ARRAY(JSON_OBJECT('label','3 秒','value','3'), JSON_OBJECT('label','5 秒','value','5'), JSON_OBJECT('label','8 秒','value','8'), JSON_OBJECT('label','10 秒','value','10')),
         NULL, 1, 1, 1, '5', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_reference_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'checkbox', '生成视频带水印', NULL, NULL, 0, 0, 0, 'false', 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_reference_to_video' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'seed', '随机种子', 'number', '可选，填写整数便于复现', NULL, NULL, 0, 0, 0, NULL, 7
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_reference_to_video' AND s.schema_version = 'v1'

  UNION ALL
  SELECT s.id, 'sourceVideo', '源视频', 'file', '上传或粘贴源视频 URL',
         JSON_OBJECT('accept','video/*'), NULL, 1, 1, 1, NULL, 1
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_video_edit' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'prompt', '编辑要求', 'textarea', '描述希望如何编辑视频',
         JSON_OBJECT('maxLength', 1200, 'core', true), JSON_OBJECT('maxLength', 1200), 1, 1, 1, NULL, 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_video_edit' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'referenceImages', '参考图', 'multi_image', '可选，上传或从素材库选择参考图',
         JSON_OBJECT('minCount',0,'maxCount',5,'accept','image/*','libraryEnabled',true,'libraryKind','image'),
         JSON_OBJECT('minCount',0,'maxCount',5), 0, 0, 0, NULL, 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_video_edit' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'resolution', '清晰度', 'select', '选择输出清晰度',
         JSON_ARRAY(JSON_OBJECT('label','720P','value','720P'), JSON_OBJECT('label','1080P','value','1080P')),
         NULL, 1, 1, 1, '720P', 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_video_edit' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'audioSetting', '音频设置', 'select', '选择音频处理方式',
         JSON_ARRAY(JSON_OBJECT('label','保留原音频','value','keep'), JSON_OBJECT('label','静音','value','mute'), JSON_OBJECT('label','自动生成','value','auto')),
         NULL, 0, 0, 0, 'keep', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_video_edit' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'checkbox', '生成视频带水印', NULL, NULL, 0, 0, 0, 'false', 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_video_edit' AND s.schema_version = 'v1'
  UNION ALL
  SELECT s.id, 'seed', '随机种子', 'number', '可选，填写整数便于复现', NULL, NULL, 0, 0, 0, NULL, 7
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'happyhorse_video_edit' AND s.schema_version = 'v1'
) seed
ON DUPLICATE KEY UPDATE
  field_name = VALUES(field_name),
  field_type = VALUES(field_type),
  placeholder = VALUES(placeholder),
  options_json = VALUES(options_json),
  validation_json = VALUES(validation_json),
  required = VALUES(required),
  execution_required = VALUES(execution_required),
  user_required = VALUES(user_required),
  default_value = VALUES(default_value),
  sort_order = VALUES(sort_order),
  status = 'ACTIVE',
  updated_at = CURRENT_TIMESTAMP;

