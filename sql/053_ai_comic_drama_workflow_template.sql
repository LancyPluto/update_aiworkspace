SET NAMES utf8mb4;

-- AI 漫剧工具种子 + 表单字段 + 已发布工作流（合并原 015_seed_ai_comic_drama_agent.sql）。

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
  '面向短剧、漫剧、分镜视频生产，按工作流生成电影感关键帧、配音、图生视频与字幕成片。',
  'ONLINE',
  3
FROM tool_categories tc
WHERE tc.category_code = 'agent'
  AND NOT EXISTS (
    SELECT 1
    FROM ai_tools t
    WHERE t.tool_code = 'ai_comic_drama_agent'
  );

UPDATE ai_tools
SET execution_handler = 'DIGITAL_HUMAN',
    output_modality = 'VIDEO',
    estimated_credit_cost = 3
WHERE tool_code = 'ai_comic_drama_agent';

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
    'resolution',
    '视频清晰度',
    'select',
    '选择图生视频清晰度；480p 成本更低、生成更快',
    JSON_ARRAY('480p', '720p 高清'),
    NULL,
    1,
    10
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

-- 已发布工作流：剧本 → 关键帧 → TTS → 图生视频 → 字幕合成 → 成片
INSERT INTO tool_workflows (
  tool_id,
  workflow_name,
  nodes_json,
  edges_json,
  groups_json,
  config_json,
  version,
  status,
  created_by,
  updated_by
)
SELECT
  t.id,
  'default',
  JSON_ARRAY(
    JSON_OBJECT(
      'id', 'start',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 40, 'y', 220),
      'data', JSON_OBJECT(
        'title', 'Start',
        'subtitle', '用户提交漫剧表单',
        'detail', '接收主题、梗概、画风、时长等输入',
        'kind', 'start',
        'nodeDefType', 'start',
        'iconName', 'play',
        'color', '#10b981',
        'parameters', JSON_OBJECT()
      )
    ),
    JSON_OBJECT(
      'id', 'field-input',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 300, 'y', 220),
      'data', JSON_OBJECT(
        'title', 'Field Input',
        'subtitle', '漫剧表单参数',
        'detail', '整理 storyTheme、plotOutline、visualStyle 等字段',
        'kind', 'input',
        'nodeDefType', 'field_input',
        'iconName', 'file-input',
        'color', '#64748b',
        'parameters', JSON_OBJECT()
      )
    ),
    JSON_OBJECT(
      'id', 'script-planner',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 560, 'y', 220),
      'data', JSON_OBJECT(
        'title', 'Script Planner',
        'subtitle', '生成单镜剧本与双语字幕',
        'detail', '根据梗概生成场景描述、对白与中英文字幕',
        'kind', 'model',
        'nodeDefType', 'llm_text',
        'iconName', 'file-text',
        'color', '#3b82f6',
        'parameters', JSON_OBJECT(
          'modelConfigId', (SELECT id FROM agent_model_configs WHERE enabled = 1 ORDER BY is_default DESC, id ASC LIMIT 1),
          'role', 'comic_script_planner'
        )
      )
    ),
    JSON_OBJECT(
      'id', 'keyframe',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 820, 'y', 220),
      'data', JSON_OBJECT(
        'title', 'Keyframe Generator',
        'subtitle', '电影感关键帧',
        'detail', '生成如漫剧分镜的写实/电影感首帧',
        'kind', 'model',
        'nodeDefType', 'image_model',
        'iconName', 'image',
        'color', '#ec4899',
        'parameters', JSON_OBJECT(
          'modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'siliconflow_image_turbo' LIMIT 1),
          'width', 1024,
          'height', 576
        )
      )
    ),
    JSON_OBJECT(
      'id', 'tts',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 1080, 'y', 80),
      'data', JSON_OBJECT(
        'title', 'Voice TTS',
        'subtitle', '角色配音',
        'detail', '根据对白生成配音音频',
        'kind', 'model',
        'nodeDefType', 'tts_model',
        'iconName', 'volume-2',
        'color', '#8b5cf6',
        'parameters', JSON_OBJECT(
          'modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'siliconflow_voice_tts' LIMIT 1)
        )
      )
    ),
    JSON_OBJECT(
      'id', 'clip-video',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 1080, 'y', 360),
      'data', JSON_OBJECT(
        'title', 'Image To Video',
        'subtitle', 'Seedance 图生视频',
        'detail', '关键帧驱动 5 秒电影感镜头',
        'kind', 'model',
        'nodeDefType', 'video_model',
        'iconName', 'video',
        'color', '#f97316',
        'parameters', JSON_OBJECT(
          'modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'seedance_video_generation' LIMIT 1),
          'resolution', '480p',
          'durationSeconds', 5,
          'aspectRatio', '16:9'
        )
      )
    ),
    JSON_OBJECT(
      'id', 'compose',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 1340, 'y', 220),
      'data', JSON_OBJECT(
        'title', 'Subtitle + FFmpeg',
        'subtitle', '烧录双语字幕',
        'detail', '合并视频、音频与中英字幕，输出 final.mp4',
        'kind', 'tool',
        'nodeDefType', 'subtitle',
        'iconName', 'captions',
        'color', '#f97316',
        'parameters', JSON_OBJECT(
          'alignmentPolicy', 'audio_duration_first',
          'watermarkText', 'AI 制作'
        )
      )
    ),
    JSON_OBJECT(
      'id', 'output',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 1600, 'y', 220),
      'data', JSON_OBJECT(
        'title', 'End',
        'subtitle', '成片交付',
        'detail', '用户侧展示可播放视频与下载',
        'kind', 'output',
        'nodeDefType', 'video_output',
        'iconName', 'video',
        'color', '#ef4444',
        'parameters', JSON_OBJECT()
      )
    )
  ),
  JSON_ARRAY(
    JSON_OBJECT('id', 'e-start-field', 'source', 'start', 'target', 'field-input', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e-field-script', 'source', 'field-input', 'target', 'script-planner', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e-script-keyframe', 'source', 'script-planner', 'target', 'keyframe', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e-script-tts', 'source', 'script-planner', 'target', 'tts', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e-keyframe-video', 'source', 'keyframe', 'target', 'clip-video', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e-tts-compose', 'source', 'tts', 'target', 'compose', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e-video-compose', 'source', 'clip-video', 'target', 'compose', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e-compose-output', 'source', 'compose', 'target', 'output', 'type', 'smoothstep')
  ),
  NULL,
  JSON_OBJECT(
    'workflowType', 'AI_COMIC_DRAMA',
    'integrationMode', 'STANDARD_TASK',
    'requiredModelConfigCodes', JSON_ARRAY(
      'siliconflow_voice_tts',
      'siliconflow_image_turbo',
      'seedance_video_generation'
    )
  ),
  1,
  'PUBLISHED',
  NULL,
  NULL
FROM ai_tools t
WHERE t.tool_code = 'ai_comic_drama_agent'
ON DUPLICATE KEY UPDATE
  nodes_json = VALUES(nodes_json),
  edges_json = VALUES(edges_json),
  config_json = VALUES(config_json),
  status = 'PUBLISHED',
  version = version + 1,
  updated_at = CURRENT_TIMESTAMP;
