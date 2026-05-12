INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'product_detail_page_copywriter',
  'AI 商品详情页文案生成器',
  tc.id,
  '帮助电商商家与运营基于商品信息与卖点，生成详情页卖点提炼、主文案、分段排版建议、关键词与合规提示。',
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
    WHERE t.tool_code = 'product_detail_page_copywriter'
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
WHERE t.tool_code = 'product_detail_page_copywriter'
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
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

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
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

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
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

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
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

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
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'specsOrAttributes',
    '规格/参数要点',
    'TEXTAREA',
    '仅填写已确认可展示的信息，勿让模型扩写未提供数据',
    NULL,
    JSON_OBJECT('maxLength', 800),
    0,
    6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'tone',
    '文案调性',
    'SELECT',
    '例如：活力种草',
    JSON_ARRAY('专业克制', '活力种草', '高端质感', '亲和直白'),
    NULL,
    1,
    7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'lengthPreference',
    '篇幅偏好',
    'SELECT',
    '例如：标准',
    JSON_ARRAY('精简', '标准', '更丰富'),
    NULL,
    1,
    8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'keywords',
    '希望包含关键词',
    'TEXTAREA',
    '例如：防晒衣、冰丝、通勤',
    NULL,
    JSON_OBJECT('maxLength', 500),
    0,
    9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'avoidWords',
    '避免使用词',
    'TEXTAREA',
    '例如：最强、第一、永久',
    NULL,
    JSON_OBJECT('maxLength', 500),
    0,
    10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'extraInfo',
    '补充信息',
    'TEXTAREA',
    '例如：促销节点、服务承诺（已核实）',
    NULL,
    JSON_OBJECT('maxLength', 800),
    0,
    11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'product_detail_page_copywriter' AND s.schema_version = 'v1'
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
  '默认商品详情页文案 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'product_detail_page_copywriter'
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
  '你是一个专业的电商商品详情页文案策划，擅长把商品卖点转化成长图文详情结构。请严格使用用户提供的商品信息与参数，不要编造材质、功效、检测数值、资质背书或价格；不要使用绝对化广告词；若用户给出避免使用词，正文中不得出现。',
  CONCAT(
    '请根据以下信息，为电商商品详情页撰写成套文案（含结构与主文）：\n\n',
    '- 商品名称：{{productName}}\n',
    '- 商品类目：{{category}}\n',
    '- 目标平台：{{targetPlatform}}\n',
    '- 目标人群：{{targetCustomer}}\n',
    '- 核心卖点：{{sellingPoints}}\n',
    '- 文案调性：{{tone}}\n',
    '- 篇幅偏好：{{lengthPreference}}\n',
    '- 规格/参数要点：{{specsOrAttributes}}\n',
    '- 希望自然融入的关键词：{{keywords}}\n',
    '- 避免使用词：{{avoidWords}}\n',
    '- 补充信息：{{extraInfo}}\n\n',
    '请严格遵循：卖点提炼用短句或条目；详情页主文案按首屏吸引—中段展开—收尾行动写成可读长文；分段排版建议用有序列表对应详情页模块顺序；关键词建议用#话题词；合规与发布提醒需提示核对材质、功效宣称与资质。\n',
    '输出必须包含且仅使用以下二级标题：## 卖点提炼、## 详情页主文案、## 分段排版建议、## 关键词建议、## 合规与发布提醒。'
  ),
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'product_detail_page_copywriter'
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
WHERE t.tool_code = 'product_detail_page_copywriter'
  AND p.prompt_code = 'default';
