SET NAMES utf8mb4;

-- Volcengine/Doubao media refinement:
-- 1) complete Seedream/Seedance media fields from Ark docs
-- 2) refresh model-level pricing rules from local price sheet

UPDATE agent_model_configs
SET
  billing_unit = 'PER_SECOND',
  unit_price = 0.17280000,
  updated_at = CURRENT_TIMESTAMP
WHERE config_code = 'volcengine-gateway-video';

UPDATE agent_model_configs
SET
  billing_unit = 'PER_CALL',
  unit_price = 0.25000000,
  updated_at = CURRENT_TIMESTAMP
WHERE config_code = 'volcengine-gateway-image';

DELETE r FROM pricing_rules r
INNER JOIN agent_model_configs m ON r.scope_type = 'MODEL' AND r.scope_ref = m.id
WHERE m.config_code IN ('volcengine-gateway-video', 'volcengine-gateway-image');

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT 'MODEL', m.id, seed.param_key, 'MULTIPLIER', seed.match_op, seed.match_value,
       seed.factor, 0, seed.priority, 1, seed.remark
FROM agent_model_configs m
JOIN (
  SELECT 'volcengine-gateway-video' AS config_code, 'model' AS param_key, 'EQ' AS match_op, 'doubao-seedance-2-0-260128' AS match_value, 5.7500 AS factor, 45 AS priority, 'Seedance 2.0 720p 无视频输入单价约 46 元/百万 token，相对 1.5 Pro 无声基线' AS remark
  UNION ALL SELECT 'volcengine-gateway-video', 'model', 'EQ', 'doubao-seedance-2-0-fast-260128', 4.6250, 45, 'Seedance 2.0 Fast 720p 无视频输入单价约 37 元/百万 token'
  UNION ALL SELECT 'volcengine-gateway-video', 'model', 'EQ', 'doubao-seedance-2-0-mini-260615', 2.8750, 45, 'Seedance 2.0 Mini 720p 无视频输入单价约 23 元/百万 token'
  UNION ALL SELECT 'volcengine-gateway-video', 'model', 'EQ', 'doubao-seedance-1-0-pro-250528', 1.8750, 45, 'Seedance 1.0 Pro 单价约 15 元/百万 token'
  UNION ALL SELECT 'volcengine-gateway-video', 'model', 'EQ', 'doubao-seedance-1-0-pro-fast-251015', 0.5250, 45, 'Seedance 1.0 Pro Fast 单价约 4.2 元/百万 token'
  UNION ALL SELECT 'volcengine-gateway-video', 'resolution', 'EQ', '480p', 0.4650, 50, '480p 相对 720p，按官方 5 秒示例 2.31/4.97 折算'
  UNION ALL SELECT 'volcengine-gateway-video', 'resolution', 'EQ', '1080p', 2.4930, 50, '1080p 相对 720p，按官方 5 秒示例 12.39/4.97 折算'
  UNION ALL SELECT 'volcengine-gateway-video', 'resolution', 'EQ', '4k', 5.0860, 50, '4K 相对 720p，按 token 公式和 4K 单价折算'
  UNION ALL SELECT 'volcengine-gateway-video', 'generateAudio', 'EQ', 'true', 2.0000, 55, 'Seedance 1.5 Pro 有声/无声官方单价约 16/8 元/百万 token'
  UNION ALL SELECT 'volcengine-gateway-image', 'count', 'VALUE', NULL, 1.0000, 40, '按生成张数 count 倍率'
  UNION ALL SELECT 'volcengine-gateway-image', 'model', 'EQ', 'doubao-seedream-5-0-260128', 0.8800, 50, 'Seedream 5.0 Lite 0.22 元/张，相对 4.5 的 0.25 元/张'
  UNION ALL SELECT 'volcengine-gateway-image', 'model', 'EQ', 'doubao-seedream-4.0', 0.8000, 50, 'Seedream 4.0 0.20 元/张，相对 4.5 的 0.25 元/张'
) seed ON seed.config_code = m.config_code;

DELETE f FROM tool_field_schema_items f
INNER JOIN tool_field_schemas s ON s.id = f.schema_id
INNER JOIN ai_tools t ON t.id = s.tool_id
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
         JSON_OBJECT('uiTier','all','uiGroup','meta','uiGroupLabel','基本信息','defaultValue','doubao-seedance-1-5-pro-251215','options',JSON_ARRAY(
           JSON_OBJECT('label','Seedance 1.5 Pro（推荐）','value','doubao-seedance-1-5-pro-251215'),
           JSON_OBJECT('label','Seedance 2.0','value','doubao-seedance-2-0-260128'),
           JSON_OBJECT('label','Seedance 2.0 Fast','value','doubao-seedance-2-0-fast-260128'),
           JSON_OBJECT('label','Seedance 2.0 Mini','value','doubao-seedance-2-0-mini-260615'),
           JSON_OBJECT('label','Seedance 1.0 Pro','value','doubao-seedance-1-0-pro-250528'),
           JSON_OBJECT('label','Seedance 1.0 Pro Fast','value','doubao-seedance-1-0-pro-fast-251015')
         )),
         NULL, 0, 0, 0, 'doubao-seedance-1-5-pro-251215', 'default', 'LOW', 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'referenceImages', '参考图片', 'multi_image', '可选，首帧/末帧或 2.0 多图参考，最多 9 张',
         JSON_OBJECT('uiTier','all','minCount',0,'maxCount',9,'accept','image/*','libraryEnabled',true,'libraryKind','image','helpText','1 张默认作为首帧；2 张可作为首尾帧；Seedance 2.0 系列支持 1-9 张 reference_image。'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'aspectRatio', '画面比例', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','16:9','options',JSON_ARRAY(JSON_OBJECT('label','智能','value','adaptive'),JSON_OBJECT('label','16:9','value','16:9'),JSON_OBJECT('label','9:16','value','9:16'),JSON_OBJECT('label','1:1','value','1:1'),JSON_OBJECT('label','4:3','value','4:3'),JSON_OBJECT('label','3:4','value','3:4'),JSON_OBJECT('label','21:9','value','21:9'))),
         NULL, 0, 0, 0, '16:9', 'default', 'LOW', 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'duration', '时长', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','5','options',JSON_ARRAY(JSON_OBJECT('label','5 秒','value','5'),JSON_OBJECT('label','10 秒','value','10'),JSON_OBJECT('label','12 秒','value','12'))),
         NULL, 0, 0, 0, '5', 'default', 'LOW', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'resolution', '分辨率', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','720p','options',JSON_ARRAY(JSON_OBJECT('label','480p','value','480p'),JSON_OBJECT('label','720p','value','720p'),JSON_OBJECT('label','1080p','value','1080p'),JSON_OBJECT('label','4K','value','4k'))),
         NULL, 0, 0, 0, '720p', 'default', 'LOW', 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'sourceVideoUrl', '参考视频', 'file', '可选，Seedance 2.0 系列支持参考视频 URL',
         JSON_OBJECT('uiTier','advanced','accept','video/*','libraryKind','video','helpText','最多 3 段参考视频，总时长建议不超过 15 秒。'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 7
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'audioUrl', '参考音频', 'file', '可选，Seedance 2.0 系列支持参考音频 URL',
         JSON_OBJECT('uiTier','advanced','accept','audio/*','libraryKind','audio','helpText','需搭配参考图或参考视频使用。'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 8
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'generateAudio', '生成音频', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','false','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','false'),JSON_OBJECT('label','开启','value','true'))),
         NULL, 0, 0, 0, 'false', 'default', 'LOW', 9
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'cameraFixed', '固定镜头', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','false','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','false'),JSON_OBJECT('label','开启','value','true'))),
         NULL, 0, 0, 0, 'false', 'default', 'LOW', 10
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','false','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','false'),JSON_OBJECT('label','开启','value','true'))),
         NULL, 0, 0, 0, 'false', 'default', 'LOW', 11
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'seed', '随机种子', 'number', '可选，填写整数便于复现',
         JSON_OBJECT('uiTier','advanced'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 12
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-video' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'negativePrompt', '反向提示词', 'textarea', '不希望出现的元素',
         JSON_OBJECT('uiTier','advanced'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 13
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
         JSON_OBJECT('uiTier','all','uiGroup','meta','uiGroupLabel','基本信息','defaultValue','doubao-seedream-4-5-251128','options',JSON_ARRAY(
           JSON_OBJECT('label','Seedream 4.5（推荐）','value','doubao-seedream-4-5-251128'),
           JSON_OBJECT('label','Seedream 5.0 Lite','value','doubao-seedream-5-0-260128'),
           JSON_OBJECT('label','Seedream 4.0','value','doubao-seedream-4.0')
         )),
         NULL, 0, 0, 0, 'doubao-seedream-4-5-251128', 'default', 'LOW', 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'aspectRatio', '画面比例', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','1:1','options',JSON_ARRAY(JSON_OBJECT('label','1:1','value','1:1'),JSON_OBJECT('label','16:9','value','16:9'),JSON_OBJECT('label','9:16','value','9:16'),JSON_OBJECT('label','4:3','value','4:3'),JSON_OBJECT('label','3:4','value','3:4'),JSON_OBJECT('label','3:2','value','3:2'),JSON_OBJECT('label','2:3','value','2:3'),JSON_OBJECT('label','21:9','value','21:9'))),
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
  SELECT s.id, 'referenceImages', '参考图片', 'multi_image', '可选，Seedream 5.0 Lite / 4.5 / 4.0 支持单图或多图输入，最多 14 张',
         JSON_OBJECT('uiTier','all','minCount',0,'maxCount',14,'accept','image/*','libraryEnabled',true,'libraryKind','image','helpText','参考图 + 组图输出总数最多 15 张；支持 URL、上传图片或素材库。'),
         NULL, 0, 0, 0, NULL, 'default', 'LOW', 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'size', '输出规格', 'radio', NULL,
         JSON_OBJECT('uiTier','all','defaultValue','2K','options',JSON_ARRAY(JSON_OBJECT('label','2K','value','2K'),JSON_OBJECT('label','3K','value','3K'),JSON_OBJECT('label','4K','value','4K'))),
         NULL, 0, 0, 0, '2K', 'default', 'LOW', 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'sequentialImageGeneration', '组图模式', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','disabled','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','disabled'),JSON_OBJECT('label','自动','value','auto'))),
         NULL, 0, 0, 0, 'disabled', 'default', 'LOW', 7
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'maxImages', '组图上限', 'number', '1-15，仅组图模式自动时生效',
         JSON_OBJECT('uiTier','advanced','min',1,'max',15,'step',1),
         NULL, 0, 0, 0, '15', 'default', 'LOW', 8
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'outputFormat', '输出格式', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','jpeg','options',JSON_ARRAY(JSON_OBJECT('label','PNG','value','png'),JSON_OBJECT('label','JPEG','value','jpeg'))),
         NULL, 0, 0, 0, 'jpeg', 'default', 'LOW', 9
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'responseFormat', '返回格式', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','url','options',JSON_ARRAY(JSON_OBJECT('label','URL','value','url'),JSON_OBJECT('label','Base64','value','b64_json'))),
         NULL, 0, 0, 0, 'url', 'default', 'LOW', 10
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
  UNION ALL
  SELECT s.id, 'watermark', '水印', 'radio', NULL,
         JSON_OBJECT('uiTier','advanced','defaultValue','false','options',JSON_ARRAY(JSON_OBJECT('label','关闭','value','false'),JSON_OBJECT('label','开启','value','true'))),
         NULL, 0, 0, 0, 'false', 'default', 'LOW', 11
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'volcengine-image' AND s.schema_version = 'v1' AND s.status = 'ACTIVE'
) seed;
