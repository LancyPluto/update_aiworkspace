INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'xiaohongshu_copywriting',
  'AI 小红书文案生成器',
  tc.id,
  '帮助商家、门店、运营人员基于少量关键信息快速生成一篇更像小红书风格、可直接修改发布的营销文案。',
  'ONLINE',
  1
FROM tool_categories tc
WHERE tc.category_code = 'copywriting'
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'xiaohongshu_copywriting'
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
WHERE t.tool_code = 'xiaohongshu_copywriting'
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
    '产品/服务名称' AS field_name,
    'TEXT' AS field_type,
    '例如：五一肩颈护理套餐' AS placeholder,
    NULL AS options_json,
    JSON_OBJECT('maxLength', 100) AS validation_json,
    1 AS required,
    1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'xiaohongshu_copywriting' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'targetCustomer',
    '目标用户',
    'TEXT',
    '例如：年轻女性、宝妈、学生党',
    NULL,
    JSON_OBJECT('maxLength', 100),
    1,
    2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'xiaohongshu_copywriting' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'style',
    '文案风格',
    'SELECT',
    '例如：种草',
    JSON_ARRAY('种草', '真实分享', '口语化', '专业'),
    NULL,
    1,
    3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'xiaohongshu_copywriting' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'sellingPoints',
    '核心卖点',
    'TEXTAREA',
    '例如：价格划算、效果明显、适合新手',
    NULL,
    JSON_OBJECT('maxLength', 500),
    1,
    4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'xiaohongshu_copywriting' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'extraInfo',
    '补充说明',
    'TEXTAREA',
    '例如：活动时间、门店位置、价格信息、禁用词',
    NULL,
    JSON_OBJECT('maxLength', 1000),
    0,
    5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'xiaohongshu_copywriting' AND s.schema_version = 'v1'
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
  '默认小红书文案 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'xiaohongshu_copywriting'
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
  '你是一个专业的小红书营销文案助手，擅长为门店商家、本地生活商家、电商运营生成更像真人分享的小红书风格文案。你要使用自然、口语化、像真实分享的表达，不要写成生硬广告。紧扣用户提供的信息，不要编造未提供的价格、功效、地址、资质或活动细节。优先突出核心卖点和适合人群，让内容具体，不空泛。输出必须严格按照指定 Markdown 结构返回，不要额外添加解释。',
  '请根据以下信息生成一篇适合发布在小红书的平台风格文案：\n\n- 产品/服务名称：{{productName}}\n- 目标用户：{{targetCustomer}}\n- 文案风格：{{style}}\n- 核心卖点：{{sellingPoints}}\n- 补充说明：{{extraInfo}}\n\n请满足以下要求：\n- 输出 3 个标题建议，标题要有吸引力但不过度夸张。\n- 正文要自然、有代入感，适合直接修改后发布。\n- 标签建议给 5 到 8 个，尽量贴合内容和目标人群。\n- 行动引导只写 1 条，语气自然，不要生硬促销。\n- 如果补充说明为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面格式输出：\n## 标题建议\n1. ...\n2. ...\n3. ...\n\n## 正文\n...\n\n## 标签建议\n#...\n#...\n#...\n\n## 行动引导\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'xiaohongshu_copywriting'
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
WHERE t.tool_code = 'xiaohongshu_copywriting'
  AND p.prompt_code = 'default';
