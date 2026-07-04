-- Consolidate Volcengine/Doubao media tools to one image tool and one video tool.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE agent_model_configs
SET is_deleted = 1,
    enabled = 0,
    agent_enabled = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND config_code NOT IN ('volcengine-gateway-video', 'volcengine-gateway-image')
  AND (
    config_code IN (
      'volcengine-seedance',
      'seedance_video_generation',
      'seedance2_0_2',
      'volcengine-seedream',
      'doubao-seedream-image-generation'
    )
    OR config_code LIKE 'volcengine-seedance-%'
    OR config_code LIKE 'volcengine-seedream-%'
  );

UPDATE ai_tools
SET is_deleted = 1,
    status = 'OFFLINE',
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code NOT IN ('volcengine-video', 'volcengine-image')
  AND (
    tool_code IN (
      'volcengine-seedance',
      'seedance_video_generation',
      'seedance2_0_2',
      'volcengine-seedream',
      'doubao-seedream-image-generation'
    )
    OR tool_code LIKE 'volcengine-seedance-%'
    OR tool_code LIKE 'volcengine-seedream-%'
  );

UPDATE ai_tools
SET is_deleted = 0,
    status = 'ONLINE',
    model_config_id = (
      SELECT id FROM agent_model_configs
      WHERE config_code = 'volcengine-gateway-video' AND is_deleted = 0
      LIMIT 1
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'volcengine-video';

UPDATE ai_tools
SET is_deleted = 0,
    status = 'ONLINE',
    model_config_id = (
      SELECT id FROM agent_model_configs
      WHERE config_code = 'volcengine-gateway-image' AND is_deleted = 0
      LIMIT 1
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'volcengine-image';

INSERT INTO tool_field_schemas (tool_id, schema_version, status)
SELECT t.id, 'v1', 'ACTIVE'
FROM ai_tools t
WHERE t.tool_code IN ('volcengine-video', 'volcengine-image')
  AND NOT EXISTS (
    SELECT 1
    FROM tool_field_schemas s
    WHERE s.tool_id = t.id AND s.schema_version = 'v1'
  );

UPDATE tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
SET s.status = 'ACTIVE',
    s.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code IN ('volcengine-video', 'volcengine-image')
  AND s.schema_version = 'v1';

DELETE i
FROM tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code IN ('volcengine-video', 'volcengine-image')
  AND s.schema_version = 'v1'
  AND s.status = 'ACTIVE';

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
  agent_fill_strategy,
  risk_level,
  sort_order,
  status
)
SELECT seed.schema_id, seed.field_key, seed.field_name, seed.field_type, seed.placeholder, seed.options_json,
       seed.validation_json, seed.required, seed.execution_required, seed.user_required, seed.default_value,
       seed.agent_fill_strategy, seed.risk_level, seed.sort_order, 'ACTIVE'
FROM (
  SELECT s.id AS schema_id, 'prompt' AS field_key, '画面描述' AS field_name, 'textarea' AS field_type,
         '描述主体、场景、镜头语言和细节' AS placeholder, JSON_OBJECT('core', true, 'uiTier', 'all') AS options_json,
         NULL AS validation_json, 1 AS required, 1 AS execution_required, 1 AS user_required,
         NULL AS default_value, 'ask_user' AS agent_fill_strategy, 'LOW' AS risk_level, 1 AS sort_order
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'model', 'Seedance 模型', 'select', NULL,
         JSON_OBJECT(
           'uiTier', 'all',
           'uiGroup', 'meta',
           'uiGroupLabel', '基本信息',
           'defaultValue', 'doubao-seedance-1-5-pro-251215',
           'options', JSON_ARRAY(
             JSON_OBJECT('label','Seedance 1.5 Pro（推荐）','value','doubao-seedance-1-5-pro-251215'),
             JSON_OBJECT('label','Seedance 2.0','value','doubao-seedance-2-0-260128'),
             JSON_OBJECT('label','Seedance 2.0 Mini','value','doubao-seedance-2-0-mini-260615'),
             JSON_OBJECT('label','Seedance 1.0 Pro','value','doubao-seedance-1-0-pro-250528'),
             JSON_OBJECT('label','Seedance 1.0 Pro Fast','value','doubao-seedance-1-0-pro-fast-251015')
           )
         ),
         NULL, 0, 0, 0, 'doubao-seedance-1-5-pro-251215', 'default', 'LOW', 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'imageUrl', '参考图片', 'image', '可选，图生视频时上传首帧',
         JSON_OBJECT('uiTier','all','libraryEnabled',true,'libraryKind','image'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'aspectRatio', '画面比例', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','16:9','options',JSON_ARRAY(JSON_OBJECT('label','16:9','value','16:9'),JSON_OBJECT('label','9:16','value','9:16'),JSON_OBJECT('label','1:1','value','1:1'))),
         NULL, 0, 0, 0, '16:9', 'default', 'LOW', 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'duration', '时长', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','5','options',JSON_ARRAY(JSON_OBJECT('label','5 秒','value','5'),JSON_OBJECT('label','10 秒','value','10'))),
         NULL, 0, 0, 0, '5', 'default', 'LOW', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'resolution', '分辨率', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','720p','options',JSON_ARRAY(JSON_OBJECT('label','480p','value','480p'),JSON_OBJECT('label','720p','value','720p'),JSON_OBJECT('label','1080p','value','1080p'))),
         NULL, 0, 0, 0, '720p', 'default', 'LOW', 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'generateAudio', '生成音频', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','false','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','false'),JSON_OBJECT('label','开启','value','true'))),
         NULL, 0, 0, 0, 'false', 'default', 'LOW', 7
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','false','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','false'),JSON_OBJECT('label','开启','value','true'))),
         NULL, 0, 0, 0, 'false', 'default', 'LOW', 8
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'negativePrompt', '反向提示词', 'textarea', '不希望出现的元素',
         JSON_OBJECT('uiTier','advanced'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 9
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'prompt', '画面描述', 'textarea', '描述画面主体、风格与细节',
         JSON_OBJECT('core', true, 'uiTier', 'all'),
         NULL, 1, 1, 1, NULL, 'ask_user', 'LOW', 1
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'model', 'Seedream 模型', 'select', NULL,
         JSON_OBJECT(
           'uiTier', 'all',
           'uiGroup', 'meta',
           'uiGroupLabel', '基本信息',
           'defaultValue', 'doubao-seedream-4-5-251128',
           'options', JSON_ARRAY(
             JSON_OBJECT('label','Seedream 4.5（推荐）','value','doubao-seedream-4-5-251128'),
             JSON_OBJECT('label','Seedream 5.0','value','doubao-seedream-5-0-260128')
           )
         ),
         NULL, 0, 0, 0, 'doubao-seedream-4-5-251128', 'default', 'LOW', 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'aspectRatio', '画面比例', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','1:1','options',JSON_ARRAY(JSON_OBJECT('label','1:1','value','1:1'),JSON_OBJECT('label','16:9','value','16:9'),JSON_OBJECT('label','9:16','value','9:16'),JSON_OBJECT('label','4:3','value','4:3'),JSON_OBJECT('label','3:4','value','3:4'))),
         NULL, 0, 0, 0, '1:1', 'default', 'LOW', 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'count', '生成张数', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','1','options',JSON_ARRAY(JSON_OBJECT('label','1 张','value','1'),JSON_OBJECT('label','2 张','value','2'),JSON_OBJECT('label','4 张','value','4'))),
         NULL, 0, 0, 0, '1', 'default', 'LOW', 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'imageUrl', '参考图片', 'image', '可选，图生图参考',
         JSON_OBJECT('uiTier','advanced','libraryEnabled',true,'libraryKind','image'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','false','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','false'),JSON_OBJECT('label','开启','value','true'))),
         NULL, 0, 0, 0, 'false', 'default', 'LOW', 7
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
) seed;
