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
  'customer_followup_script_generator',
  'AI 客户跟进话术生成器',
  tc.id,
  '帮助销售、客服和私域运营基于客户类型、跟进阶段、客户痛点、产品价值和跟进目标生成自然克制的客户跟进话术。',
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
    WHERE t.tool_code = 'customer_followup_script_generator'
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
WHERE t.tool_code = 'customer_followup_script_generator'
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
  SELECT s.id AS schema_id, 'customerType' AS field_key, '客户类型' AS field_name, 'SELECT' AS field_type,
         '例如：新咨询客户' AS placeholder,
         JSON_ARRAY('新咨询客户', '意向客户', '沉默客户', '老客户', '流失客户', '售后客户') AS options_json,
         NULL AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'followupStage', '跟进阶段', 'SELECT', '例如：二次跟进',
         JSON_ARRAY('初次跟进', '二次跟进', '报价后跟进', '成交前确认', '复购跟进', '流失召回'),
         NULL, 1, 2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'communicationChannel', '沟通渠道', 'SELECT', '例如：微信/企微',
         JSON_ARRAY('微信/企微', '电话', '短信', '社群', '站内信', '通用'),
         NULL, 1, 3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'customerProfile', '客户画像', 'TEXTAREA', '例如：25-35 岁通勤女性，之前咨询过防晒外套',
         NULL, JSON_OBJECT('maxLength', 800), 0, 4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'customerPainPoints', '客户痛点/需求', 'TEXTAREA', '例如：怕晒、怕闷、想要通勤显瘦',
         NULL, JSON_OBJECT('maxLength', 800), 1, 5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'productOrService', '产品/服务', 'TEXTAREA', '例如：轻薄防晒外套',
         NULL, JSON_OBJECT('maxLength', 800), 1, 6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'valueProposition', '核心价值/卖点', 'TEXTAREA', '例如：轻薄透气、显瘦版型、可收纳、多色可选',
         NULL, JSON_OBJECT('maxLength', 800), 1, 7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'followupGoal', '跟进目标', 'SELECT', '例如：促成下单',
         JSON_ARRAY('补充信息', '建立信任', '促成下单', '预约到店', '复购提醒', '流失召回'),
         NULL, 1, 8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'tone', '话术风格', 'SELECT', '例如：亲和自然',
         JSON_ARRAY('亲和自然', '专业克制', '简洁直接', '温和提醒', '顾问式沟通'),
         NULL, 1, 9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'lastInteraction', '上次沟通情况', 'TEXTAREA', '例如：客户问过浅色是否透、尺码怎么选，但还没有下单',
         NULL, JSON_OBJECT('maxLength', 800), 0, 10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'objectionOrConcern', '客户顾虑', 'TEXTAREA', '例如：担心闷热、尺码不合适',
         NULL, JSON_OBJECT('maxLength', 800), 0, 11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'callToAction', '行动引导', 'TEXT', '例如：引导客户提供身高体重，确认尺码后下单',
         NULL, JSON_OBJECT('maxLength', 200), 0, 12
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'avoidWords', '避免使用词', 'TEXTAREA', '例如：保证、一定有效、最低价',
         NULL, JSON_OBJECT('maxLength', 500), 0, 13
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo', '补充信息', 'TEXTAREA', '例如：不要频繁催促，强调如实参考和可选方案',
         NULL, JSON_OBJECT('maxLength', 800), 0, 14
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'customer_followup_script_generator' AND s.schema_version = 'v1'
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
  '默认客户跟进话术 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'customer_followup_script_generator'
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
  '你是一个专业的销售与私域客户跟进话术助手，擅长根据客户类型、跟进阶段、沟通渠道、客户痛点、产品/服务价值和跟进目标，生成自然、克制、可执行的跟进话术。请严格使用用户提供的信息，不要编造客户历史、付款意愿、优惠、库存、承诺、资质或服务权益。',
  '请根据以下信息生成一套客户跟进话术：\n\n- 客户类型：{{customerType}}\n- 跟进阶段：{{followupStage}}\n- 沟通渠道：{{communicationChannel}}\n- 客户画像：{{customerProfile}}\n- 客户痛点/需求：{{customerPainPoints}}\n- 产品/服务：{{productOrService}}\n- 核心价值/卖点：{{valueProposition}}\n- 跟进目标：{{followupGoal}}\n- 话术风格：{{tone}}\n- 上次沟通情况：{{lastInteraction}}\n- 客户顾虑：{{objectionOrConcern}}\n- 行动引导：{{callToAction}}\n- 避免使用词：{{avoidWords}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 话术要适配沟通渠道。\n- 不要编造客户历史、购买意愿、价格优惠、库存、售后权益、资质或承诺。\n- 跟进话术要自然克制，避免骚扰式、压迫式、诱导式表达。\n- 异议处理要围绕用户提供的顾虑，不承诺未提供事项。\n- 触达节奏建议要给出合理间隔和下一步动作。\n- 如果可选字段为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n## 客户情况分析\n- ...\n\n## 跟进话术\n...\n\n## 异议处理话术\n- 问：...\n  答：...\n\n## 触达节奏建议\n1. ...\n2. ...\n\n## 跟进记录建议\n- ...\n\n## 合规提醒\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'customer_followup_script_generator'
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
WHERE t.tool_code = 'customer_followup_script_generator'
  AND p.prompt_code = 'default';
