INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'short_video_script_generator',
  'AI 短视频脚本生成器',
  tc.id,
  '帮助内容创作者和电商运营基于视频主题、平台、人群、卖点和转化目标生成可拍摄的短视频脚本。',
  'ONLINE',
  0
FROM tool_categories tc
WHERE tc.category_code = COALESCE(
    (SELECT category_code FROM tool_categories WHERE category_code = 'ecommerce' LIMIT 1),
    'copywriting'
  )
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'short_video_script_generator'
  );

INSERT INTO tool_field_schemas (
  tool_id,
  schema_version,
  status
)
SELECT
  t.id,
  'v1',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'short_video_script_generator'
  AND NOT EXISTS (
    SELECT 1
    FROM tool_field_schemas s
    WHERE s.tool_id = t.id
      AND s.schema_version = 'v1'
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
  sort_order,
  status
)
SELECT
  schema_seed.schema_id,
  schema_seed.field_key,
  schema_seed.field_name,
  schema_seed.field_type,
  schema_seed.placeholder,
  schema_seed.options_json,
  schema_seed.validation_json,
  schema_seed.required,
  schema_seed.sort_order,
  'ACTIVE'
FROM (
  SELECT s.id AS schema_id, 'videoTopic' AS field_key, '视频主题' AS field_name, 'TEXT' AS field_type,
         '例如：夏季通勤防晒穿搭' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 120) AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetPlatform', '目标平台', 'SELECT', '例如：抖音',
         JSON_ARRAY('抖音', '快手', '小红书', '视频号', 'B站', '通用'),
         NULL, 1, 2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetAudience', '目标人群', 'TEXT', '例如：25-35 岁通勤女性',
         NULL, JSON_OBJECT('maxLength', 120), 1, 3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'promotionObject', '推广对象', 'TEXT', '例如：轻薄防晒外套',
         NULL, JSON_OBJECT('maxLength', 120), 1, 4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'coreSellingPoints', '核心卖点', 'TEXTAREA', '例如：轻薄透气、防晒、显瘦、可收纳',
         NULL, JSON_OBJECT('maxLength', 800), 1, 5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'scriptStyle', '脚本风格', 'SELECT', '例如：种草带货',
         JSON_ARRAY('种草带货', '知识科普', '剧情反转', '经验分享', '测评对比', '门店探访'),
         NULL, 1, 6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'videoLength', '视频时长', 'SELECT', '例如：30 秒',
         JSON_ARRAY('15 秒', '30 秒', '60 秒', '90 秒'),
         NULL, 1, 7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'shootingScenario', '拍摄场景', 'TEXT', '例如：通勤路上、办公室',
         NULL, JSON_OBJECT('maxLength', 200), 0, 8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'callToAction', '行动引导', 'TEXT', '例如：点击橱窗查看颜色和尺码',
         NULL, JSON_OBJECT('maxLength', 200), 0, 9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'keywords', '希望包含关键词', 'TEXTAREA', '例如：防晒衣、通勤穿搭、夏季轻薄',
         NULL, JSON_OBJECT('maxLength', 500), 0, 10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'avoidWords', '避免使用词', 'TEXTAREA', '例如：最强、第一、永久有效',
         NULL, JSON_OBJECT('maxLength', 500), 0, 11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo', '补充信息', 'TEXTAREA', '例如：不要夸大防晒效果',
         NULL, JSON_OBJECT('maxLength', 800), 0, 12
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_script_generator' AND s.schema_version = 'v1'
) schema_seed
WHERE NOT EXISTS (
  SELECT 1
  FROM tool_field_schema_items i
  WHERE i.schema_id = schema_seed.schema_id
    AND i.field_key = schema_seed.field_key
);

INSERT INTO tool_prompts (
  tool_id,
  prompt_code,
  prompt_name,
  status
)
SELECT
  t.id,
  'default',
  '默认短视频脚本 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'short_video_script_generator'
  AND NOT EXISTS (
    SELECT 1
    FROM tool_prompts p
    WHERE p.tool_id = t.id
      AND p.prompt_code = 'default'
  );

INSERT INTO tool_prompt_versions (
  prompt_id,
  version_no,
  system_prompt,
  user_prompt_template,
  output_format,
  status,
  published_at
)
SELECT
  p.id,
  'v1',
  '你是一个专业的短视频脚本策划，擅长根据主题、平台、人群、卖点和转化目标，生成可拍摄的短视频脚本。请严格使用用户提供的信息，不要编造功效、价格、资质、检测数据或平台背书，不要使用绝对化广告词。',
  '请根据以下信息生成一条可拍摄的短视频脚本：\n\n- 视频主题：{{videoTopic}}\n- 目标平台：{{targetPlatform}}\n- 目标人群：{{targetAudience}}\n- 推广对象：{{promotionObject}}\n- 核心卖点：{{coreSellingPoints}}\n- 脚本风格：{{scriptStyle}}\n- 视频时长：{{videoLength}}\n- 拍摄场景：{{shootingScenario}}\n- 行动引导：{{callToAction}}\n- 希望包含关键词：{{keywords}}\n- 避免使用词：{{avoidWords}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 开场钩子要在前 3 秒抓住注意力。\n- 分镜脚本必须包含镜头顺序或时间段，便于拍摄执行。\n- 口播文案要自然、可读、适合目标平台。\n- 拍摄与剪辑建议要具体到镜头、画面、节奏或字幕呈现。\n- 标题与标签建议要贴合平台搜索和推荐语境。\n- 不要编造未提供的功效、价格、资质、检测数据、库存、销量或平台背书。\n- 如果可选字段为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n## 开场钩子\n...\n\n## 分镜脚本\n1. ...\n2. ...\n\n## 口播文案\n...\n\n## 拍摄与剪辑建议\n- ...\n- ...\n\n## 标题与标签建议\n- 标题：...\n- 标签：#... #...\n\n## 合规提醒\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'short_video_script_generator'
  AND p.prompt_code = 'default'
  AND NOT EXISTS (
    SELECT 1
    FROM tool_prompt_versions v
    WHERE v.prompt_id = p.id
      AND v.version_no = 'v1'
  );

UPDATE tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
JOIN tool_prompt_versions v ON v.prompt_id = p.id AND v.version_no = 'v1'
SET p.active_version_id = v.id
WHERE t.tool_code = 'short_video_script_generator'
  AND p.prompt_code = 'default';
