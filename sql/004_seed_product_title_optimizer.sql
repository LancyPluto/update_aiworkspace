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
  'product_title_optimizer',
  'AI 商品标题优化器',
  tc.id,
  '帮助电商商家和运营人员基于商品信息、平台场景、目标人群和核心卖点生成更适合搜索和转化的商品标题。',
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
    WHERE t.tool_code = 'product_title_optimizer'
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
WHERE t.tool_code = 'product_title_optimizer'
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
  SELECT
    s.id AS schema_id,
    'productName' AS field_key,
    '商品名称' AS field_name,
    'TEXT' AS field_type,
    '例如：轻薄防晒外套' AS placeholder,
    NULL AS options_json,
    JSON_OBJECT('maxLength', 120) AS validation_json,
    1 AS required,
    1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'category',
    '商品类目',
    'TEXT',
    '例如：女装/防晒衣',
    NULL,
    JSON_OBJECT('maxLength', 120),
    1,
    2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'targetPlatform',
    '目标平台',
    'SELECT',
    '例如：淘宝',
    JSON_ARRAY('淘宝', '拼多多', '抖音', '小红书', '通用'),
    NULL,
    1,
    3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'targetCustomer',
    '目标人群',
    'TEXT',
    '例如：夏季通勤女性',
    NULL,
    JSON_OBJECT('maxLength', 120),
    1,
    4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'sellingPoints',
    '核心卖点',
    'TEXTAREA',
    '例如：轻薄透气、防晒、显瘦、可收纳',
    NULL,
    JSON_OBJECT('maxLength', 800),
    1,
    5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'keywords',
    '希望包含关键词',
    'TEXTAREA',
    '例如：防晒衣、冰丝、夏季、显瘦',
    NULL,
    JSON_OBJECT('maxLength', 500),
    0,
    6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'avoidWords',
    '避免使用词',
    'TEXTAREA',
    '例如：最强、永久、第一',
    NULL,
    JSON_OBJECT('maxLength', 500),
    0,
    7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'style',
    '标题风格',
    'SELECT',
    '例如：搜索友好',
    JSON_ARRAY('搜索友好', '转化导向', '简洁专业'),
    NULL,
    1,
    8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'extraInfo',
    '补充信息',
    'TEXTAREA',
    '例如：适合日常通勤和短途出游',
    NULL,
    JSON_OBJECT('maxLength', 800),
    0,
    9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_title_optimizer' AND s.schema_version = 'v1'
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
  '默认商品标题优化 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'product_title_optimizer'
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
  '你是一个专业的电商商品标题优化助手，擅长根据商品信息、平台场景、目标人群和核心卖点，生成搜索友好且有转化力的商品标题。请严格使用用户提供的信息，不要编造材质、功效、资质、检测数据或平台背书，不要使用绝对化广告词。',
  '请根据以下商品信息优化电商商品标题：\n\n- 商品名称：{{productName}}\n- 商品类目：{{category}}\n- 目标平台：{{targetPlatform}}\n- 目标人群：{{targetCustomer}}\n- 核心卖点：{{sellingPoints}}\n- 希望包含关键词：{{keywords}}\n- 避免使用词：{{avoidWords}}\n- 标题风格：{{style}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 输出 3 个优化标题，并从中选择 1 个最推荐标题。\n- 标题要兼顾搜索关键词、商品卖点和点击转化。\n- 不要使用“最强、第一、永久、100%”等绝对化或高风险表达。\n- 不要编造未提供的材质、功效、检测资质、品牌背书或价格信息。\n- 如果可选字段为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n## 优化标题\n1. ...\n2. ...\n3. ...\n\n## 推荐标题\n...\n\n## 优化理由\n- ...\n- ...\n\n## 关键词建议\n#...\n#...\n\n## 使用提醒\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'product_title_optimizer'
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
WHERE t.tool_code = 'product_title_optimizer'
  AND p.prompt_code = 'default';

