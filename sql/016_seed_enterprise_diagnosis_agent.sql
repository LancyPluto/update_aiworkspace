SET NAMES utf8mb4;

INSERT INTO tool_categories (category_code, category_name, sort_order, status)
VALUES ('agent', '智能体', 2, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  category_name = VALUES(category_name),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO ai_tools (
  tool_code,
  tool_name,
  category_id,
  description,
  status,
  estimated_credit_cost
)
SELECT
  'enterprise_diagnosis_agent',
  '企业诊断智能体',
  tc.id,
  '用户只需输入企业名称，即可生成企业信息梳理、经营诊断、风险识别和行动建议报告，支持结果页渲染与文档下载。',
  'ONLINE',
  3
FROM tool_categories tc
WHERE tc.category_code = 'agent'
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'enterprise_diagnosis_agent'
  );

UPDATE ai_tools
SET
  tool_name = '企业诊断智能体',
  description = '用户只需输入企业名称，即可生成企业信息梳理、经营诊断、风险识别和行动建议报告，支持结果页渲染与文档下载。',
  status = 'ONLINE',
  estimated_credit_cost = 3
WHERE tool_code = 'enterprise_diagnosis_agent';

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
WHERE t.tool_code = 'enterprise_diagnosis_agent'
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
  s.id,
  'companyName',
  '企业名称',
  'text',
  '请输入企业全称，例如：杭州某某科技有限公司',
  NULL,
  JSON_OBJECT('maxLength', 120),
  1,
  1,
  'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'enterprise_diagnosis_agent'
  AND s.schema_version = 'v1'
  AND NOT EXISTS (
    SELECT 1
    FROM tool_field_schema_items i
    WHERE i.schema_id = s.id
      AND i.field_key = 'companyName'
  );

UPDATE tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  i.field_name = '企业名称',
  i.field_type = 'text',
  i.placeholder = '请输入企业全称，例如：杭州某某科技有限公司',
  i.options_json = NULL,
  i.validation_json = JSON_OBJECT('maxLength', 120),
  i.required = 1,
  i.sort_order = 1,
  i.status = 'ACTIVE'
WHERE t.tool_code = 'enterprise_diagnosis_agent'
  AND s.schema_version = 'v1'
  AND i.field_key = 'companyName';

UPDATE tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET i.status = 'INACTIVE'
WHERE t.tool_code = 'enterprise_diagnosis_agent'
  AND s.schema_version = 'v1'
  AND i.field_key <> 'companyName';

INSERT INTO tool_prompts (
  tool_id,
  prompt_code,
  prompt_name,
  status
)
SELECT
  t.id,
  'default',
  '默认企业诊断报告 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'enterprise_diagnosis_agent'
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
  '你是一个企业经营诊断顾问。用户只输入企业名称，你需要调用当前大模型自身可用的联网搜索/检索能力，查询该企业的公开最新信息，再生成经营诊断报告。不要使用数字人、视频、图片或任何专用媒体生成链路。所有事实必须尽量给出来源名称或链接；如果当前模型环境无法联网，必须明确说明“联网检索不可用”，并把需要核验的信息列为待核验，不得编造实时新闻、融资、工商、财务、诉讼、舆情等事实。',
  '请联网查询并为以下企业生成一份企业诊断报告。\n\n企业名称：{{companyName}}\n\n检索要求：\n1. 优先查询企业官网、工商/企业信息平台、招聘平台、新闻报道、社媒/内容平台、应用商店、客户评价和竞品公开信息。\n2. 报告中涉及的最新事实要标注来源名称、链接或“来源待核验”。\n3. 如果无法联网，请明确说明联网不可用，只基于企业名称做框架化诊断，不要虚构具体事实。\n\n请使用 Markdown 输出完整报告，必须包含以下章节：\n# {{companyName}} 企业诊断报告\n## 1. 执行摘要\n给出 3-5 条核心判断，并区分“已检索到的信息”和“待核验判断”。\n## 2. 最新公开信息汇总\n整理企业基本信息、官网/产品、工商登记、融资、招聘、新闻动态、社媒声量、客户评价、竞品动态和经营线索。每条尽量带来源。\n## 3. 信息可信度与缺口\n说明哪些信息可信度高，哪些信息不足，并列出需要继续核验的问题。\n## 4. 企业画像与商业模式判断\n基于公开信息判断行业、客户、产品/服务、收入模式、渠道和竞争位置。\n## 5. 经营现状诊断\n从增长、产品、客户、交付、组织、财务、竞争和风险八个维度分析。\n## 6. 关键问题优先级\n用 P0/P1/P2 标注问题、影响、证据来源、验证方式和建议负责人。\n## 7. 经营建议\n给出短期止血、中期增长、长期战略三类建议，每条建议说明适用前提、执行动作和验收指标。\n## 8. 30/60/90 天行动计划\n用表格输出目标、动作、负责人角色、指标和预期结果。\n## 9. 附录：检索来源\n列出本次使用或建议核验的来源。\n\n要求：不要空泛口号；不要伪造来源；把不确定性写清楚。',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'enterprise_diagnosis_agent'
  AND p.prompt_code = 'default'
  AND NOT EXISTS (
    SELECT 1
    FROM tool_prompt_versions v
    WHERE v.prompt_id = p.id
      AND v.version_no = 'v1'
  );

UPDATE tool_prompt_versions v
JOIN tool_prompts p ON p.id = v.prompt_id
JOIN ai_tools t ON t.id = p.tool_id
SET
  v.system_prompt = '你是一个企业经营诊断顾问。用户只输入企业名称，你需要调用当前大模型自身可用的联网搜索/检索能力，查询该企业的公开最新信息，再生成经营诊断报告。不要使用数字人、视频、图片或任何专用媒体生成链路。所有事实必须尽量给出来源名称或链接；如果当前模型环境无法联网，必须明确说明“联网检索不可用”，并把需要核验的信息列为待核验，不得编造实时新闻、融资、工商、财务、诉讼、舆情等事实。',
  v.user_prompt_template = '请联网查询并为以下企业生成一份企业诊断报告。\n\n企业名称：{{companyName}}\n\n检索要求：\n1. 优先查询企业官网、工商/企业信息平台、招聘平台、新闻报道、社媒/内容平台、应用商店、客户评价和竞品公开信息。\n2. 报告中涉及的最新事实要标注来源名称、链接或“来源待核验”。\n3. 如果无法联网，请明确说明联网不可用，只基于企业名称做框架化诊断，不要虚构具体事实。\n\n请使用 Markdown 输出完整报告，必须包含以下章节：\n# {{companyName}} 企业诊断报告\n## 1. 执行摘要\n给出 3-5 条核心判断，并区分“已检索到的信息”和“待核验判断”。\n## 2. 最新公开信息汇总\n整理企业基本信息、官网/产品、工商登记、融资、招聘、新闻动态、社媒声量、客户评价、竞品动态和经营线索。每条尽量带来源。\n## 3. 信息可信度与缺口\n说明哪些信息可信度高，哪些信息不足，并列出需要继续核验的问题。\n## 4. 企业画像与商业模式判断\n基于公开信息判断行业、客户、产品/服务、收入模式、渠道和竞争位置。\n## 5. 经营现状诊断\n从增长、产品、客户、交付、组织、财务、竞争和风险八个维度分析。\n## 6. 关键问题优先级\n用 P0/P1/P2 标注问题、影响、证据来源、验证方式和建议负责人。\n## 7. 经营建议\n给出短期止血、中期增长、长期战略三类建议，每条建议说明适用前提、执行动作和验收指标。\n## 8. 30/60/90 天行动计划\n用表格输出目标、动作、负责人角色、指标和预期结果。\n## 9. 附录：检索来源\n列出本次使用或建议核验的来源。\n\n要求：不要空泛口号；不要伪造来源；把不确定性写清楚。',
  v.output_format = 'MARKDOWN',
  v.status = 'ACTIVE'
WHERE t.tool_code = 'enterprise_diagnosis_agent'
  AND p.prompt_code = 'default'
  AND v.version_no = 'v1';

UPDATE tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
JOIN tool_prompt_versions v ON v.prompt_id = p.id AND v.version_no = 'v1'
SET p.active_version_id = v.id
WHERE t.tool_code = 'enterprise_diagnosis_agent'
  AND p.prompt_code = 'default';
