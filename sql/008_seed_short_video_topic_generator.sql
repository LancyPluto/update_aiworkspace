INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'short_video_topic_generator',
  'AI 短视频选题生成器',
  tc.id,
  '帮助内容创作者和电商运营基于账号定位、平台、人群、内容目标和产品信息生成可执行短视频选题。',
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
    WHERE t.tool_code = 'short_video_topic_generator'
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
WHERE t.tool_code = 'short_video_topic_generator'
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
  SELECT s.id AS schema_id, 'accountPositioning' AS field_key, '账号/品牌定位' AS field_name, 'TEXTAREA' AS field_type,
         '例如：女装通勤穿搭店铺账号，主打简洁实穿的日常穿搭' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 800) AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetPlatform', '目标平台', 'SELECT', '例如：抖音',
         JSON_ARRAY('抖音', '快手', '小红书', '视频号', 'B站', '通用'),
         NULL, 1, 2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetAudience', '目标人群', 'TEXT', '例如：25-35 岁通勤女性',
         NULL, JSON_OBJECT('maxLength', 120), 1, 3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'contentGoal', '内容目标', 'SELECT', '例如：种草转化',
         JSON_ARRAY('涨粉曝光', '互动评论', '种草转化', '品牌认知', '门店引流', '私域沉淀'),
         NULL, 1, 4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'topicDirection', '选题方向', 'TEXTAREA', '例如：夏季穿搭、防晒、通勤场景',
         NULL, JSON_OBJECT('maxLength', 800), 1, 5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'productOrService', '产品/服务信息', 'TEXTAREA', '例如：轻薄防晒外套，主打轻薄透气、显瘦、可收纳',
         NULL, JSON_OBJECT('maxLength', 800), 0, 6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'topicCount', '选题数量', 'SELECT', '例如：10 个',
         JSON_ARRAY('5 个', '8 个', '10 个', '15 个'),
         NULL, 1, 7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'stylePreference', '内容风格', 'SELECT', '例如：实用干货',
         JSON_ARRAY('实用干货', '种草安利', '剧情反转', '经验分享', '测评对比', '轻松搞笑'),
         NULL, 1, 8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'avoidTopics', '避免方向', 'TEXTAREA', '例如：夸张挑战、低俗擦边、绝对化功效',
         NULL, JSON_OBJECT('maxLength', 500), 0, 9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'keywords', '希望包含关键词', 'TEXTAREA', '例如：防晒衣、通勤穿搭、夏季轻薄',
         NULL, JSON_OBJECT('maxLength', 500), 0, 10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo', '补充信息', 'TEXTAREA', '例如：防晒相关表达需以检测报告和实物吊牌为准',
         NULL, JSON_OBJECT('maxLength', 800), 0, 11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'short_video_topic_generator' AND s.schema_version = 'v1'
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
  '默认短视频选题 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'short_video_topic_generator'
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
  '你是一个专业的短视频内容选题策划，擅长根据账号定位、平台、人群、内容目标和产品信息，生成具体、可拍、可转化的短视频选题。请不要编造实时热点、平台数据、销量、功效、资质或检测信息，不要输出低俗擦边或绝对化表达。',
  '请根据以下信息生成一组可执行的短视频选题：\n\n- 账号/品牌定位：{{accountPositioning}}\n- 目标平台：{{targetPlatform}}\n- 目标人群：{{targetAudience}}\n- 内容目标：{{contentGoal}}\n- 选题方向：{{topicDirection}}\n- 产品/服务信息：{{productOrService}}\n- 选题数量：{{topicCount}}\n- 内容风格：{{stylePreference}}\n- 避免方向：{{avoidTopics}}\n- 希望包含关键词：{{keywords}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 选题必须具体可拍，能直接交给内容同事继续写脚本。\n- 选题应贴合账号定位、目标平台、目标人群和内容目标，不要泛泛而谈。\n- 推荐优先级要说明先做哪些选题以及原因。\n- 创作角度要给出痛点、场景、冲突、对比或教程等可执行方向。\n- 标题与标签建议要贴合平台搜索和推荐语境。\n- 不要编造实时热点、平台数据、销量、功效、检测数据或资质背书。\n- 如果可选字段为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n## 选题清单\n1. ...\n2. ...\n3. ...\n\n## 推荐优先级\n- ...\n\n## 创作角度\n- ...\n- ...\n\n## 标题与标签建议\n- 标题：...\n- 标签：#... #...\n\n## 执行建议\n...\n\n## 风险提醒\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'short_video_topic_generator'
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
WHERE t.tool_code = 'short_video_topic_generator'
  AND p.prompt_code = 'default';
