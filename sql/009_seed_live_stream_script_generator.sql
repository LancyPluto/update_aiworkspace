SET NAMES utf8mb4;

INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'live_stream_script_generator',
  'AI 直播话术生成器',
  tc.id,
  '帮助电商主播和运营基于直播主题、平台、人群、商品/服务、卖点和直播目标生成可执行直播话术。',
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
    WHERE t.tool_code = 'live_stream_script_generator'
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
WHERE t.tool_code = 'live_stream_script_generator'
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
  SELECT s.id AS schema_id, 'liveTheme' AS field_key, '直播主题' AS field_name, 'TEXT' AS field_type,
         '例如：夏季通勤防晒穿搭专场' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 120) AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetPlatform', '目标平台', 'SELECT', '例如：抖音',
         JSON_ARRAY('淘宝直播', '抖音', '快手', '小红书', '视频号', '通用'),
         NULL, 1, 2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetAudience', '目标人群', 'TEXT', '例如：25-35 岁通勤女性',
         NULL, JSON_OBJECT('maxLength', 120), 1, 3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'productOrService', '产品/服务', 'TEXTAREA', '例如：轻薄防晒外套',
         NULL, JSON_OBJECT('maxLength', 800), 1, 4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'sellingPoints', '核心卖点', 'TEXTAREA', '例如：轻薄透气、显瘦版型、可收纳、多色可选',
         NULL, JSON_OBJECT('maxLength', 800), 1, 5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'liveGoal', '直播目标', 'SELECT', '例如：种草转化',
         JSON_ARRAY('涨粉互动', '种草转化', '新品首发', '清库存', '门店引流', '私域沉淀'),
         NULL, 1, 6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'liveDuration', '直播时长', 'SELECT', '例如：60 分钟',
         JSON_ARRAY('30 分钟', '45 分钟', '60 分钟', '90 分钟', '120 分钟'),
         NULL, 1, 7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'tone', '话术风格', 'SELECT', '例如：亲和种草',
         JSON_ARRAY('亲和种草', '专业讲解', '高能促单', '轻松聊天', '门店导购'),
         NULL, 1, 8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'promotionMechanism', '活动机制', 'TEXTAREA', '例如：前 50 名赠收纳袋，具体以直播间说明为准',
         NULL, JSON_OBJECT('maxLength', 800), 0, 9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'interactionFocus', '互动重点', 'TEXTAREA', '例如：尺码、颜色、通勤搭配、短途出游场景',
         NULL, JSON_OBJECT('maxLength', 500), 0, 10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'avoidWords', '避免使用词', 'TEXTAREA', '例如：最强、第一、永久防晒',
         NULL, JSON_OBJECT('maxLength', 500), 0, 11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo', '补充信息', 'TEXTAREA', '例如：防晒相关表达需要提醒以检测报告和实物吊牌为准',
         NULL, JSON_OBJECT('maxLength', 800), 0, 12
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'live_stream_script_generator' AND s.schema_version = 'v1'
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
  '默认直播话术 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'live_stream_script_generator'
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
  '你是一个专业的电商直播话术策划，擅长根据直播主题、平台、人群、商品/服务、核心卖点和直播目标，生成可直接口播和执行的直播话术。请严格使用用户提供的信息，不要编造价格、库存、销量、功效、资质、检测数据或平台背书，不要使用绝对化广告词。',
  '请根据以下信息生成一套可用于直播间执行的直播话术：\n\n- 直播主题：{{liveTheme}}\n- 目标平台：{{targetPlatform}}\n- 目标人群：{{targetAudience}}\n- 产品/服务：{{productOrService}}\n- 核心卖点：{{sellingPoints}}\n- 直播目标：{{liveGoal}}\n- 直播时长：{{liveDuration}}\n- 话术风格：{{tone}}\n- 活动机制：{{promotionMechanism}}\n- 互动重点：{{interactionFocus}}\n- 避免使用词：{{avoidWords}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 话术要适合直播间实时口播，句子自然、有节奏。\n- 产品讲解要围绕用户已提供的卖点，不要扩写未提供的材质、效果、资质或检测数据。\n- 互动引导要能激发评论、停留和咨询。\n- 促单转化话术可以强调行动，但不得编造价格、库存、销量、限时优惠或平台背书。\n- 异议处理至少覆盖尺码/适用性、价格/价值、材质/效果、售后/服务等方向。\n- 直播节奏安排要按直播时长拆分阶段。\n- 如果可选字段为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n## 开场话术\n...\n\n## 产品讲解话术\n- ...\n- ...\n\n## 互动引导话术\n- ...\n- ...\n\n## 促单转化话术\n...\n\n## 异议处理话术\n- 问：...\n  答：...\n\n## 直播节奏安排\n1. ...\n2. ...\n\n## 合规提醒\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'live_stream_script_generator'
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
WHERE t.tool_code = 'live_stream_script_generator'
  AND p.prompt_code = 'default';

