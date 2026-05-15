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
  'ecommerce_campaign_planner',
  'AI 电商活动方案生成器',
  tc.id,
  '帮助电商运营团队基于活动目标、活动周期、货品范围和渠道资源生成可执行活动方案，支持节奏安排、玩法设计和复盘指标建议。',
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
    WHERE t.tool_code = 'ecommerce_campaign_planner'
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
WHERE t.tool_code = 'ecommerce_campaign_planner'
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
  SELECT s.id AS schema_id, 'campaignGoal' AS field_key, '活动目标' AS field_name, 'SELECT' AS field_type,
         '例如：转化' AS placeholder, JSON_ARRAY('拉新', '促活', '转化', '清库存') AS options_json,
         NULL AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'campaignName' AS field_key, '活动主题' AS field_name, 'TEXT' AS field_type, '例如：夏季清凉节' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 120) AS validation_json, 1 AS required, 2 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetPlatform' AS field_key, '目标平台' AS field_name, 'SELECT' AS field_type, '例如：抖音' AS placeholder,
         JSON_ARRAY('淘宝', '拼多多', '抖音', '快手', '通用') AS options_json,
         NULL AS validation_json, 1 AS required, 3 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'campaignPeriod' AS field_key, '活动周期' AS field_name, 'TEXT' AS field_type, '例如：7天预热+3天爆发' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 120) AS validation_json, 1 AS required, 4 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetAudience' AS field_key, '目标人群' AS field_name, 'TEXT' AS field_type, '例如：18-30岁女性用户' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 200) AS validation_json, 1 AS required, 5 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'productScope' AS field_key, '活动货品范围' AS field_name, 'TEXTAREA' AS field_type, '例如：主推防晒衣，连带冰袖和防晒帽' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 1200) AS validation_json, 1 AS required, 6 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'budgetRange' AS field_key, '预算范围' AS field_name, 'TEXT' AS field_type, '例如：3万-5万' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 120) AS validation_json, 0 AS required, 7 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'discountPolicy' AS field_key, '优惠机制' AS field_name, 'TEXTAREA' AS field_type, '例如：满减+限时券+直播间福利' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 1000) AS validation_json, 0 AS required, 8 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'channelResources' AS field_key, '资源位/渠道' AS field_name, 'TEXTAREA' AS field_type, '例如：直播间、短视频、店铺首页、私域群' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 1000) AS validation_json, 0 AS required, 9 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo' AS field_key, '补充信息' AS field_name, 'TEXTAREA' AS field_type, '例如：避免夸大承诺，不要给出违规营销话术' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 1200) AS validation_json, 0 AS required, 10 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ecommerce_campaign_planner' AND s.schema_version = 'v1'
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
  '默认电商活动方案 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'ecommerce_campaign_planner'
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
  '你是一个专业的电商活动策划助手。你需要根据用户输入生成可执行的活动方案，覆盖目标拆解、阶段节奏、玩法设计、资源分配、投放建议和复盘指标。不要编造未提供的数据、政策、平台官方资源或不合规承诺。',
  '请根据以下信息生成电商活动方案：\n\n- 活动目标：{{campaignGoal}}\n- 活动主题：{{campaignName}}\n- 目标平台：{{targetPlatform}}\n- 活动周期：{{campaignPeriod}}\n- 目标人群：{{targetAudience}}\n- 活动货品范围：{{productScope}}\n- 预算范围：{{budgetRange}}\n- 优惠机制：{{discountPolicy}}\n- 资源位/渠道：{{channelResources}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 方案要可执行，给出阶段节奏和关键动作。\n- 资源分配建议应结合输入的预算和渠道信息。\n- 不要给出违规营销建议，不要夸大承诺。\n- 指标建议要可跟踪，便于复盘。\n\n请严格按照下面 Markdown 结构输出：\n## 活动目标与策略概览\n...\n\n## 活动节奏与阶段安排\n...\n\n## 玩法设计与资源分配\n...\n\n## 投放与转化建议\n...\n\n## 核心指标与复盘建议\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'ecommerce_campaign_planner'
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
WHERE t.tool_code = 'ecommerce_campaign_planner'
  AND p.prompt_code = 'default';
