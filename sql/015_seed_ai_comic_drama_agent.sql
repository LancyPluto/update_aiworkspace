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
  'ai_comic_drama_agent',
  'AI 漫剧生成智能体',
  tc.id,
  '面向短剧、漫剧、分镜视频生产，生成故事设定、分集剧情、角色设定、分镜脚本和图视频生成提示词。',
  'ONLINE',
  2
FROM tool_categories tc
WHERE tc.category_code = 'agent'
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'ai_comic_drama_agent'
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
WHERE t.tool_code = 'ai_comic_drama_agent'
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
  SELECT
    s.id AS schema_id,
    'storyTheme' AS field_key,
    '漫剧主题' AS field_name,
    'text' AS field_type,
    '例如：穿越后我靠 AI 开店逆袭' AS placeholder,
    NULL AS options_json,
    JSON_OBJECT('maxLength', 120) AS validation_json,
    1 AS required,
    1 AS sort_order
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'genre',
    '题材类型',
    'select',
    '选择漫剧题材',
    JSON_ARRAY('都市逆袭', '甜宠恋爱', '悬疑反转', '科幻脑洞', '古风权谋', '校园成长'),
    NULL,
    1,
    2
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'targetAudience',
    '目标受众',
    'select',
    '选择主要观看人群',
    JSON_ARRAY('18-24 岁女性', '18-30 岁男性', '下沉市场短剧用户', '亲子家庭', '泛二次元用户', '职场人群'),
    NULL,
    1,
    3
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'episodeCount',
    '集数规划',
    'select',
    '选择需要规划的集数',
    JSON_ARRAY('3 集', '5 集', '8 集', '12 集'),
    NULL,
    1,
    4
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'episodeDuration',
    '单集时长',
    'select',
    '选择单集目标时长',
    JSON_ARRAY('15 秒', '30 秒', '60 秒', '90 秒'),
    NULL,
    1,
    5
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'mainCharacters',
    '主要角色',
    'textarea',
    '写清楚主角、反派、关键配角；没有想法可写“帮我设计”',
    NULL,
    JSON_OBJECT('maxLength', 800),
    0,
    6
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'plotOutline',
    '剧情梗概',
    'textarea',
    '例如：主角被背叛后获得新能力，用三集完成反转复仇',
    NULL,
    JSON_OBJECT('maxLength', 1200),
    1,
    7
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'visualStyle',
    '画风风格',
    'select',
    '选择画面风格',
    JSON_ARRAY('国漫厚涂', '日漫赛璐璐', '韩漫条漫', '电影感写实', 'Q 版轻喜剧', '暗黑悬疑'),
    NULL,
    1,
    8
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'aspectRatio',
    '画面比例',
    'select',
    '选择发布画幅',
    JSON_ARRAY('9:16 竖屏', '16:9 横屏', '1:1 方形', '3:4 条漫'),
    NULL,
    1,
    9
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'outputDetail',
    '输出颗粒度',
    'select',
    '选择结果详细程度',
    JSON_ARRAY('基础策划', '分集脚本', '分镜脚本', '分镜 + 图视频提示词'),
    NULL,
    1,
    10
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'referenceMaterial',
    '参考素材',
    'textarea',
    '可选：填写参考作品、角色设定、世界观、禁用元素等',
    NULL,
    JSON_OBJECT('maxLength', 1500),
    0,
    11
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'

  UNION ALL

  SELECT
    s.id,
    'negativePrompt',
    '负面要求',
    'textarea',
    '例如：不要血腥暴力、不要低俗擦边、不要角色设定前后矛盾',
    NULL,
    JSON_OBJECT('maxLength', 800),
    0,
    12
  FROM tool_field_schemas s
  JOIN ai_tools t ON t.id = s.tool_id
  WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1'
) seed
WHERE NOT EXISTS (
  SELECT 1
  FROM tool_field_schema_items i
  WHERE i.schema_id = seed.schema_id
    AND i.field_key = seed.field_key
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
  '默认 AI 漫剧生成 Prompt',
  'ACTIVE'
FROM ai_tools t
WHERE t.tool_code = 'ai_comic_drama_agent'
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
  '你是一个 AI 漫剧生成智能体，擅长把短剧和漫画创意转成可生产的系列设定、分集剧情、分镜脚本和图视频生成提示词。输出必须结构化、可执行，并避免低俗、血腥、侵权和未成年人不适宜内容。',
  '请基于以下需求生成 AI 漫剧制作方案。\n\n漫剧主题：{{storyTheme}}\n题材类型：{{genre}}\n目标受众：{{targetAudience}}\n集数规划：{{episodeCount}}\n单集时长：{{episodeDuration}}\n主要角色：{{mainCharacters}}\n剧情梗概：{{plotOutline}}\n画风风格：{{visualStyle}}\n画面比例：{{aspectRatio}}\n输出颗粒度：{{outputDetail}}\n参考素材：{{referenceMaterial}}\n负面要求：{{negativePrompt}}\n\n请输出：\n1. 项目一句话卖点\n2. 世界观和核心冲突\n3. 主要角色小传与人物关系\n4. 分集大纲，每集包含钩子、冲突、反转和结尾悬念\n5. 单集样例脚本，包含旁白、对白、画面动作和音效建议\n6. 分镜表，包含镜号、景别、画面描述、台词、时长、转场\n7. 角色图提示词、场景图提示词、关键分镜图提示词和图生视频提示词\n8. 制作注意事项和风险规避',
  'MARKDOWN',
  'ACTIVE',
  NOW()
FROM tool_prompts p
JOIN ai_tools t ON t.id = p.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent'
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
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND p.prompt_code = 'default';
