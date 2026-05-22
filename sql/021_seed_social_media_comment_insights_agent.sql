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
  estimated_credit_cost,
  tool_type,
  execution_handler,
  input_modality,
  output_modality,
  config_note
)
SELECT
  'social_media_comment_insights_agent',
  '社交网络评论洞察智能体',
  tc.id,
  '围绕产品名、品牌或目标人群，分析小红书、抖音等公开社交媒体评论信号，输出用户期望、痛点顾虑和产品运营建议报告。',
  'ONLINE',
  3,
  'TEXT_GENERATION',
  'TEXT_GENERATION',
  'TEXT',
  'TEXT',
  'MVP 使用管理侧配置的大模型进行公开信息检索与评论洞察总结；遇到平台登录、反爬或不可访问评论时会明确标注限制。'
FROM tool_categories tc
WHERE tc.category_code = 'agent'
  AND NOT EXISTS (
    SELECT 1 FROM ai_tools t WHERE t.tool_code = 'social_media_comment_insights_agent'
  );

UPDATE ai_tools
SET
  tool_name = '社交网络评论洞察智能体',
  description = '围绕产品名、品牌或目标人群，分析小红书、抖音等公开社交媒体评论信号，输出用户期望、痛点顾虑和产品运营建议报告。',
  status = 'ONLINE',
  estimated_credit_cost = 3,
  tool_type = 'TEXT_GENERATION',
  execution_handler = 'TEXT_GENERATION',
  input_modality = 'TEXT',
  output_modality = 'TEXT',
  config_note = 'MVP 使用管理侧配置的大模型进行公开信息检索与评论洞察总结；遇到平台登录、反爬或不可访问评论时会明确标注限制。'
WHERE tool_code = 'social_media_comment_insights_agent';

INSERT INTO tool_field_schemas (tool_id, schema_version, status)
SELECT t.id, 'v1', 'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'social_media_comment_insights_agent'
  AND NOT EXISTS (
    SELECT 1 FROM tool_field_schemas s WHERE s.tool_id = t.id AND s.schema_version = 'v1'
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
  seed.schema_id,
  seed.field_key,
  seed.field_name,
  seed.field_type,
  seed.placeholder,
  seed.options_json,
  seed.validation_json,
  seed.required,
  seed.sort_order,
  'ACTIVE'
FROM (
  SELECT s.id AS schema_id, 'productName' AS field_key, '产品/服务/品牌名称' AS field_name, 'text' AS field_type,
         '例如：某款护肤面膜、智能眼罩、低糖酸奶、AI 写作工具' AS placeholder,
         NULL AS options_json, JSON_OBJECT('maxLength', 120) AS validation_json, 1 AS required, 1 AS sort_order
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id, 'targetAudience', '目标人群', 'text',
         '例如：一线城市年轻女性、宝妈、大学生、B 端运营负责人',
         NULL, JSON_OBJECT('maxLength', 160), 1, 2
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id, 'platforms', '重点平台', 'select',
         '选择本次优先分析的平台范围',
         JSON_ARRAY('小红书 + 抖音', '小红书', '抖音', '微博 + 小红书 + 抖音', '全平台公开信息'),
         NULL, 1, 3
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id, 'analysisGoal', '分析目标', 'select',
         '选择更关心的输出方向',
         JSON_ARRAY('产品改进建议', '用户需求洞察', '内容种草方向', '竞品评论对比', '上市前市场验证'),
         NULL, 1, 4
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id, 'region', '关注地区', 'text',
         '可选，例如：全国、上海、华东、一线城市',
         NULL, JSON_OBJECT('maxLength', 80), 0, 5
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id, 'sourceUrls', '公开链接或关键词线索', 'textarea',
         '可选，粘贴小红书/抖音/微博/B站公开链接、话题名、竞品名或搜索关键词；每行一条',
         NULL, JSON_OBJECT('maxLength', 1200), 0, 6
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id, 'commentSamples', '评论样本', 'textarea',
         '可选，粘贴已收集到的评论内容，智能体会优先基于这些真实样本总结',
         NULL, JSON_OBJECT('maxLength', 3000), 0, 7
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT s.id, 'extraInfo', '补充背景', 'textarea',
         '可选，例如价格带、竞品、渠道、已有假设、希望重点验证的问题',
         NULL, JSON_OBJECT('maxLength', 1000), 0, 8
  FROM tool_field_schemas s JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'social_media_comment_insights_agent' AND s.schema_version = 'v1'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM tool_field_schema_items i
  WHERE i.schema_id = seed.schema_id AND i.field_key = seed.field_key
);

UPDATE tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET i.status = IF(i.field_key IN (
    'productName',
    'targetAudience',
    'platforms',
    'analysisGoal',
    'region',
    'sourceUrls',
    'commentSamples',
    'extraInfo'
  ), 'ACTIVE', 'INACTIVE')
WHERE t.tool_code = 'social_media_comment_insights_agent'
  AND s.schema_version = 'v1';

INSERT INTO agent_tool_descriptor_extension (
  tool_id,
  tool_code,
  agent_enabled,
  agent_recommendable,
  agent_auto_callable,
  confirmation_policy,
  risk_level,
  keywords_json,
  example_prompts_json,
  applicable_scenarios_json,
  output_type
)
SELECT
  t.id,
  t.tool_code,
  1,
  1,
  0,
  'auto',
  'medium',
  JSON_ARRAY('社交媒体评论', '小红书评论', '抖音评论', '用户洞察', '评论分析', '舆情分析', '产品建议', '目标人群反馈'),
  JSON_ARRAY(
    '帮我分析小红书和抖音上用户对智能眼罩的评论，总结产品改进建议',
    '看看年轻宝妈对低糖酸奶有什么期待和顾虑',
    '根据社交媒体评论总结这款护肤面膜的用户痛点'
  ),
  JSON_ARRAY('新品调研', '产品改进', '社媒运营选题', '竞品评论分析', '目标人群需求洞察'),
  'text'
FROM ai_tools t
WHERE t.tool_code = 'social_media_comment_insights_agent'
ON DUPLICATE KEY UPDATE
  tool_id = VALUES(tool_id),
  agent_enabled = VALUES(agent_enabled),
  agent_recommendable = VALUES(agent_recommendable),
  agent_auto_callable = VALUES(agent_auto_callable),
  confirmation_policy = VALUES(confirmation_policy),
  risk_level = VALUES(risk_level),
  keywords_json = VALUES(keywords_json),
  example_prompts_json = VALUES(example_prompts_json),
  applicable_scenarios_json = VALUES(applicable_scenarios_json),
  output_type = VALUES(output_type);
