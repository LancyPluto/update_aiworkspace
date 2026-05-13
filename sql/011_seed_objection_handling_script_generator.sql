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
  'objection_handling_script_generator',
  'AI 异议处理话术生成器',
  tc.id,
  '帮助销售、客服和私域运营基于客户异议、沟通渠道、销售阶段、产品价值和处理目标生成自然克制的异议处理话术。',
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
    WHERE t.tool_code = 'objection_handling_script_generator'
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
WHERE t.tool_code = 'objection_handling_script_generator'
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
  SELECT s.id AS schema_id, 'objectionText' AS field_key, '客户异议原话' AS field_name, 'TEXTAREA' AS field_type,
         '例如：价格有点贵，我再考虑一下' AS placeholder, NULL AS options_json,
         JSON_OBJECT('maxLength', 800) AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'objectionType', '异议类型', 'SELECT', '例如：价格异议',
         JSON_ARRAY('价格异议', '效果异议', '信任异议', '时机异议', '竞品对比', '售后顾虑', '适配顾虑', '其他'),
         NULL, 1, 2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'communicationChannel', '沟通渠道', 'SELECT', '例如：微信/企微',
         JSON_ARRAY('微信/企微', '电话', '短信', '社群', '站内信', '通用'),
         NULL, 1, 3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'customerType', '客户类型', 'SELECT', '例如：意向客户',
         JSON_ARRAY('新咨询客户', '意向客户', '沉默客户', '老客户', '流失客户', '售后客户'),
         NULL, 1, 4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'salesStage', '销售阶段', 'SELECT', '例如：报价后跟进',
         JSON_ARRAY('需求确认', '方案介绍', '报价后跟进', '成交前确认', '复购沟通', '售后沟通'),
         NULL, 1, 5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'customerContext', '客户背景', 'TEXTAREA', '例如：客户之前问过浅色和尺码，对通勤场景比较关注',
         NULL, JSON_OBJECT('maxLength', 800), 0, 6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'productOrService', '产品/服务', 'TEXTAREA', '例如：轻薄防晒外套',
         NULL, JSON_OBJECT('maxLength', 800), 1, 7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'valueProposition', '核心价值/卖点', 'TEXTAREA', '例如：轻薄透气、显瘦版型、可收纳、多色可选',
         NULL, JSON_OBJECT('maxLength', 800), 1, 8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'handlingGoal', '处理目标', 'SELECT', '例如：继续沟通',
         JSON_ARRAY('澄清需求', '继续沟通', '建立信任', '推动试用', '促成下单', '预约到店'),
         NULL, 1, 9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'tone', '话术风格', 'SELECT', '例如：亲和克制',
         JSON_ARRAY('亲和克制', '专业理性', '顾问式沟通', '简洁直接', '温和解释'),
         NULL, 1, 10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'proofPoints', '可用佐证', 'TEXTAREA', '例如：有尺码表和上身实拍图，可按身高体重给参考',
         NULL, JSON_OBJECT('maxLength', 800), 0, 11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'avoidWords', '避免使用词', 'TEXTAREA', '例如：保证、最低价、一定有效',
         NULL, JSON_OBJECT('maxLength', 500), 0, 12
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id AS schema_id, 'extraInfo', '补充信息', 'TEXTAREA', '例如：不要强压成交，先帮客户判断是否适合',
         NULL, JSON_OBJECT('maxLength', 800), 0, 13
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'objection_handling_script_generator' AND s.schema_version = 'v1'
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
  '默认异议处理话术 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'objection_handling_script_generator'
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
  '你是一个专业的销售异议处理话术助手，擅长根据客户异议、沟通渠道、销售阶段、产品/服务价值和处理目标，生成自然、克制、可执行的回应话术。请先承接客户顾虑，再补充价值和追问引导；不要争辩、压迫、诱导，不要编造客户历史、价格优惠、库存、服务权益、效果承诺或资质背书。',
  '请根据以下信息生成一套客户异议处理话术：\n\n- 客户异议原话：{{objectionText}}\n- 异议类型：{{objectionType}}\n- 沟通渠道：{{communicationChannel}}\n- 客户类型：{{customerType}}\n- 销售阶段：{{salesStage}}\n- 客户背景：{{customerContext}}\n- 产品/服务：{{productOrService}}\n- 核心价值/卖点：{{valueProposition}}\n- 处理目标：{{handlingGoal}}\n- 话术风格：{{tone}}\n- 可用佐证：{{proofPoints}}\n- 避免使用词：{{avoidWords}}\n- 补充信息：{{extraInfo}}\n\n请严格遵循以下要求：\n- 先判断异议背后的可能顾虑，再给回应策略。\n- 推荐话术要先认可客户感受，再补充价值点或可验证信息，最后用轻量问题继续沟通。\n- 追问引导要帮助澄清真实顾虑，不连续逼单。\n- 替代表达至少给出更温和和更直接两种版本。\n- 后续动作建议要给出可执行步骤，不建议高频打扰。\n- 如果可选字段为空，请忽略该字段，不要在结果中提到“未提供”。\n\n请严格按照下面 Markdown 结构输出：\n## 异议判断\n- ...\n\n## 回应策略\n- ...\n\n## 推荐话术\n...\n\n## 追问引导\n- ...\n\n## 替代表达\n- 更温和：...\n- 更直接：...\n\n## 后续动作建议\n1. ...\n2. ...\n\n## 合规提醒\n...',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'objection_handling_script_generator'
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
WHERE t.tool_code = 'objection_handling_script_generator'
  AND p.prompt_code = 'default';

