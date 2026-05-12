INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'store_campaign_planner',
  'AI 门店活动策划器',
  tc.id,
  '帮助门店店长和本地生活商家基于门店类型、活动目标、周期、预算、客群和主推商品/服务生成可落地的门店活动方案。',
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
    WHERE t.tool_code = 'store_campaign_planner'
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
WHERE t.tool_code = 'store_campaign_planner'
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
  SELECT s.id AS schema_id, 'storeType' AS field_key, '门店类型' AS field_name, 'SELECT' AS field_type,
         '例如：服装零售' AS placeholder,
         JSON_ARRAY('服装零售', '餐饮门店', '美业门店', '教育培训', '本地生活', '母婴门店', '家居建材', '其他') AS options_json,
         NULL AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'storeNameOrPositioning', '门店名称/定位', 'TEXTAREA', '例如：社区女装店，主打通勤穿搭',
         NULL, JSON_OBJECT('maxLength', 800), 1, 2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'campaignGoal', '活动目标', 'SELECT', '例如：到店转化',
         JSON_ARRAY('拉新到店', '到店转化', '老客复购', '会员激活', '新品推广', '清库存', '社群沉淀'),
         NULL, 1, 3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'campaignTheme', '活动主题', 'TEXT', '例如：夏季通勤焕新节',
         NULL, JSON_OBJECT('maxLength', 120), 1, 4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'campaignPeriod', '活动周期', 'TEXT', '例如：预热 3 天 + 正式 2 天',
         NULL, JSON_OBJECT('maxLength', 120), 1, 5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'targetCustomers', '目标客群', 'TEXTAREA', '例如：周边 3 公里通勤女性、老会员',
         NULL, JSON_OBJECT('maxLength', 800), 1, 6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'productsOrServices', '主推商品/服务', 'TEXTAREA', '例如：防晒外套、通勤衬衫、搭配套装',
         NULL, JSON_OBJECT('maxLength', 800), 1, 7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'budgetRange', '预算范围', 'SELECT', '例如：1000-3000 元',
         JSON_ARRAY('500 元以内', '500-1000 元', '1000-3000 元', '3000-5000 元', '5000 元以上'),
         NULL, 1, 8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'availableResources', '可用资源', 'TEXTAREA', '例如：会员群、店员 3 人、橱窗、朋友圈',
         NULL, JSON_OBJECT('maxLength', 800), 0, 9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'promotionMechanism', '活动机制', 'TEXTAREA', '例如：满 399 赠丝巾，老客带新享小礼',
         NULL, JSON_OBJECT('maxLength', 800), 0, 10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'channels', '触达渠道', 'TEXTAREA', '例如：门店海报、朋友圈、社群、短视频',
         NULL, JSON_OBJECT('maxLength', 500), 0, 11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'constraints', '限制条件', 'TEXTAREA', '例如：不做大额折扣，库存有限',
         NULL, JSON_OBJECT('maxLength', 800), 0, 12
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo', '补充信息', 'TEXTAREA', '例如：希望提升老客复购和到店试穿',
         NULL, JSON_OBJECT('maxLength', 800), 0, 13
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'store_campaign_planner' AND s.schema_version = 'v1'
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
  '默认门店活动策划 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'store_campaign_planner'
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
  '你是一个专业的线下门店活动策划助手，擅长根据门店类型、活动目标、活动周期、预算、主推商品/服务、目标客群和可用资源，生成可落地执行的门店活动方案。请严格使用用户提供的信息，不要编造库存、折扣、客流、销售额、会员数据或平台数据。',
  '请根据以下信息生成一套可落地的线下门店活动方案：\n\n- 门店类型：{{storeType}}\n- 门店名称/定位：{{storeNameOrPositioning}}\n- 活动目标：{{campaignGoal}}\n- 活动主题：{{campaignTheme}}\n- 活动周期：{{campaignPeriod}}\n- 目标客群：{{targetCustomers}}\n- 主推商品/服务：{{productsOrServices}}\n- 预算范围：{{budgetRange}}\n- 可用资源：{{availableResources}}\n- 活动机制：{{promotionMechanism}}\n- 触达渠道：{{channels}}\n- 限制条件：{{constraints}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 方案必须适合线下门店执行，不能只给线上投放建议。\n- 活动玩法要结合门店人力、预算、渠道和主推商品/服务。\n- 执行排期要按活动周期拆分阶段，明确预热、正式执行和复盘动作。\n- 物料与话术建议要能直接给店员、社群、朋友圈或门店海报使用。\n- 人员分工要考虑店长、店员、导购或兼职等角色。\n- 复盘指标要包含到店、成交、客单、会员、复购或线索等可观察指标。\n- 如果可选字段为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n## 活动总览\n- ...\n\n## 客群策略\n- ...\n\n## 活动玩法设计\n1. ...\n2. ...\n\n## 执行排期\n1. ...\n2. ...\n\n## 物料与话术建议\n- ...\n\n## 人员分工\n- ...\n\n## 复盘指标\n- ...\n\n## 风险提醒\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'store_campaign_planner'
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
WHERE t.tool_code = 'store_campaign_planner'
  AND p.prompt_code = 'default';
