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
  'wechat_longform_generator',
  'AI 公众号/长文生成器',
  tc.id,
  '帮助运营和商家基于结构化输入快速生成可二次编辑的公众号长文草稿，适合活动推广、知识科普和转化文案场景。',
  'ONLINE',
  0
FROM tool_categories tc
WHERE tc.category_code = 'copywriting'
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'wechat_longform_generator'
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
WHERE t.tool_code = 'wechat_longform_generator'
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
    'topic' AS field_key,
    '文章主题' AS field_name,
    'TEXT' AS field_type,
    '例如：夏季门店引流活动' AS placeholder,
    NULL AS options_json,
    JSON_OBJECT('maxLength', 120) AS validation_json,
    1 AS required,
    1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'audience',
    '目标读者',
    'TEXT',
    '例如：25-35 岁女性白领',
    NULL,
    JSON_OBJECT('maxLength', 120),
    1,
    2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'goal',
    '写作目标',
    'SELECT',
    '例如：引流',
    JSON_ARRAY('引流', '科普', '转化', '品牌认知'),
    NULL,
    1,
    3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'tone',
    '语气风格',
    'SELECT',
    '例如：专业',
    JSON_ARRAY('专业', '故事化', '干货', '亲切'),
    NULL,
    1,
    4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'lengthLevel',
    '篇幅档位',
    'SELECT',
    '例如：中',
    JSON_ARRAY('短', '中', '长'),
    NULL,
    1,
    5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'keyPoints',
    '核心要点',
    'TEXTAREA',
    '例如：用户痛点、方案亮点、案例证明、常见疑问解答',
    NULL,
    JSON_OBJECT('maxLength', 1200),
    1,
    6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'cta',
    '行动引导',
    'TEXT',
    '例如：点击预约领取到店体验名额',
    NULL,
    JSON_OBJECT('maxLength', 120),
    0,
    7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id AS schema_id,
    'extraInfo',
    '补充信息',
    'TEXTAREA',
    '例如：活动时间、服务范围、品牌语调禁用词等',
    NULL,
    JSON_OBJECT('maxLength', 1200),
    0,
    8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'wechat_longform_generator' AND s.schema_version = 'v1'
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
  '默认公众号长文 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'wechat_longform_generator'
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
  '你是一个专业的公众号长文写作助手。你需要根据用户输入生成结构清晰、可读性强、可直接二次编辑发布的长文草稿。请严格使用给定信息，不要编造事实，不要输出额外解释。',
  '请根据以下输入生成一篇公众号长文：\n\n- 文章主题：{{topic}}\n- 目标读者：{{audience}}\n- 写作目标：{{goal}}\n- 语气风格：{{tone}}\n- 篇幅档位：{{lengthLevel}}\n- 核心要点：{{keyPoints}}\n- 行动引导：{{cta}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 内容逻辑完整，避免空话套话。\n- 语言风格与目标读者匹配，避免明显营销腔。\n- 不要编造未提供的事实、数据、地址、价格、政策信息。\n- 适当分段并加小标题，确保可读性。\n- 如果行动引导或补充信息为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n# 标题\n...\n\n## 导语\n...\n\n## 正文\n### 小节1\n...\n### 小节2\n...\n\n## 总结\n...\n\n## 行动引导\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'wechat_longform_generator'
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
WHERE t.tool_code = 'wechat_longform_generator'
  AND p.prompt_code = 'default';

