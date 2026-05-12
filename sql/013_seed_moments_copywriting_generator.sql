INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'moments_copywriting_generator',
  'AI 朋友圈文案生成器',
  tc.id,
  '帮助商家、门店和私域运营基于主题、场景、目标人群和核心卖点生成自然、简洁、可直接发布或二次编辑的朋友圈文案。',
  'ONLINE',
  0
FROM tool_categories tc
WHERE tc.category_code = COALESCE(
    (SELECT category_code FROM tool_categories WHERE category_code = 'copywriting' LIMIT 1),
    'ecommerce'
  )
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'moments_copywriting_generator'
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
WHERE t.tool_code = 'moments_copywriting_generator'
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
  SELECT s.id AS schema_id, 'topic' AS field_key, '文案主题' AS field_name, 'TEXT' AS field_type,
         '例如：周末肩颈放松活动' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 120) AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetAudience', '目标人群', 'TEXT', '例如：久坐上班族、老会员、附近新客',
         NULL, JSON_OBJECT('maxLength', 120), 1, 2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'tone', '文案风格', 'SELECT', '例如：亲切',
         JSON_ARRAY('亲切', '种草', '真实分享', '专业', '轻松', '促销'),
         NULL, 1, 3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'scene', '发布场景', 'SELECT', '例如：活动宣传',
         JSON_ARRAY('活动宣传', '新品上线', '节日问候', '客户案例', '门店日常', '福利通知', '通用'),
         NULL, 1, 4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'sellingPoints', '核心卖点', 'TEXTAREA', '例如：限时体验价、到店即用、适合上班族放松',
         NULL, JSON_OBJECT('maxLength', 800), 1, 5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'lengthLevel', '文案长度', 'SELECT', '例如：短',
         JSON_ARRAY('短', '中'),
         NULL, 1, 6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'cta', '行动引导', 'TEXT', '例如：私信预约体验名额',
         NULL, JSON_OBJECT('maxLength', 200), 0, 7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo', '补充信息', 'TEXTAREA', '例如：活动仅限本周末，避免硬广语气',
         NULL, JSON_OBJECT('maxLength', 800), 0, 8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'moments_copywriting_generator' AND s.schema_version = 'v1'
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
  '默认朋友圈文案 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'moments_copywriting_generator'
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
  '你是一个专业的朋友圈文案助手。请根据用户输入生成简洁、自然、可直接发布的朋友圈文案，不要编造未提供的信息，不要输出与任务无关的解释。文案正文必须像真实朋友圈内容，不要出现“文案正文”“表情建议”“话题标签”“行动引导”等栏目词。',
  '请根据以下信息生成朋友圈文案：\n\n- 文案主题：{{topic}}\n- 目标人群：{{targetAudience}}\n- 文案风格：{{tone}}\n- 发布场景：{{scene}}\n- 核心卖点：{{sellingPoints}}\n- 文案长度：{{lengthLevel}}\n- 行动引导：{{cta}}\n- 补充信息：{{extraInfo}}\n\n请严格按以下 Markdown 结构输出，结构标题仅供系统解析，正文内容本身必须可直接复制发布：\n## 文案正文\n输出一段自然朋友圈文案，不要带小标题，不要解释写作思路。\n\n## 表情建议\n只输出适合拼接在文案后的表情符号，多个表情连续排列，不要说明。\n\n## 话题标签\n输出 2-4 个话题标签，用空格分隔。\n\n## 行动引导\n输出一句可直接放在朋友圈末尾的行动引导，不要带标题。',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'moments_copywriting_generator'
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
WHERE t.tool_code = 'moments_copywriting_generator'
  AND p.prompt_code = 'default';
