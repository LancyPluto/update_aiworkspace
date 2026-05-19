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
  'digital_human_agent',
  'AI 数字人视频生成智能体',
  tc.id,
  '基于硅基流动视频生成接口，把用户输入的数字人口播需求生成视频任务，并返回可下载的视频链接。',
  'ONLINE',
  3
FROM tool_categories tc
WHERE tc.category_code = 'agent'
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'digital_human_agent'
  );

UPDATE ai_tools
SET execution_handler = 'DIGITAL_HUMAN',
    output_modality = 'VIDEO',
    estimated_credit_cost = 3
WHERE tool_code = 'digital_human_agent';

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
WHERE t.tool_code = 'digital_human_agent'
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
    'videoTopic' AS field_key,
    '视频主题' AS field_name,
    'text' AS field_type,
    '例如：新品护肤套装 30 秒种草口播' AS placeholder,
    NULL AS options_json,
    JSON_OBJECT('maxLength', 120) AS validation_json,
    1 AS required,
    1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'script',
    '口播脚本',
    'textarea',
    '写清楚数字人要说的内容，建议 60 到 180 字',
    NULL,
    JSON_OBJECT('maxLength', 1000),
    1,
    2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'avatarStyle',
    '数字人形象',
    'select',
    '选择数字人视觉风格',
    JSON_ARRAY('职业主播', '科技感主持人', '亲和力导购', '知识博主'),
    NULL,
    1,
    3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'scene',
    '视频场景',
    'select',
    '选择数字人所在场景',
    JSON_ARRAY('直播间', '产品展示台', '办公室', '纯色演播室'),
    NULL,
    1,
    4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'aspectRatio',
    '画面比例',
    'select',
    '选择视频比例',
    JSON_ARRAY('16:9 横屏', '9:16 竖屏', '1:1 方形'),
    NULL,
    1,
    5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'duration',
    '视频时长要求',
    'select',
    '选择期望成片时长。当前模型会先生成短片段，较长时长需要后续多段生成与拼接。',
    JSON_ARRAY('5 秒', '10 秒', '15 秒', '30 秒', '60 秒'),
    NULL,
    1,
    6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'resolution',
    '视频清晰度',
    'select',
    '选择生成视频清晰度；480p 成本更低、生成更快',
    JSON_ARRAY('480p', '720p 高清'),
    NULL,
    1,
    7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'brandName',
    '品牌/产品',
    'text',
    '例如：澄光实验室补水精华',
    NULL,
    JSON_OBJECT('maxLength', 120),
    0,
    7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'referenceImageUrl',
    '参考形象图 URL',
    'text',
    '可选：填写数字人参考形象图 URL，填写后会走图生视频模型',
    NULL,
    JSON_OBJECT('maxLength', 500),
    0,
    8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'visualRequirements',
    '画面要求',
    'textarea',
    '例如：明亮干净、人物半身出镜、带产品特写，不要夸张特效',
    NULL,
    JSON_OBJECT('maxLength', 800),
    0,
    9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'negativePrompt',
    '负面提示词',
    'textarea',
    '例如：画面变形、字幕错乱、手部异常、低清晰度',
    NULL,
    JSON_OBJECT('maxLength', 500),
    0,
    10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'digital_human_agent' AND s.schema_version = 'v1'
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
  '默认数字人视频 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'digital_human_agent'
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
  '你是一个数字人视频生成智能体，负责把用户输入转成适合视频生成模型的清晰画面描述。',
  '视频主题：{{videoTopic}}\n口播脚本：{{script}}\n数字人形象：{{avatarStyle}}\n视频场景：{{scene}}\n画面比例：{{aspectRatio}}\n视频时长要求：{{duration}}\n品牌/产品：{{brandName}}\n画面要求：{{visualRequirements}}\n负面提示词：{{negativePrompt}}',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'digital_human_agent'
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
WHERE t.tool_code = 'digital_human_agent'
  AND p.prompt_code = 'default';
