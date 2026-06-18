SET NAMES utf8mb4;

-- 修复 AI 漫剧发布工作流的模型能力绑定：
--   script-planner -> Agnes 2.0 Flash（文本）
--   keyframe      -> Agnes Image 2.1 Flash（图片）
--   clip-video    -> Agnes Video v2.0（视频）
--   tts           -> 中国移动 MoMA CosyVoice（文本转语音）
-- TeleAI/TeleSpeechASR 是语音转文字模型，不能用于 TTS。
SET @comic_text_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'agnes-2.0-flash' AND enabled = 1 LIMIT 1
);
SET @comic_image_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'agnes-image-2.1-flash' AND enabled = 1 LIMIT 1
);
SET @comic_video_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'agnes-video-v2.0' AND enabled = 1 LIMIT 1
);
SET @comic_tts_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'moma_cosyvoice'
    AND provider = 'moma_cosyvoice'
    AND model_name = 'CosyVoice'
    AND enabled = 1
  LIMIT 1
);

-- 067 曾发布无配音版工作流；全新环境缺少 TTS 时在这里补回。
UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.nodes_json = JSON_ARRAY_APPEND(
  CAST(w.nodes_json AS JSON),
  '$',
  JSON_OBJECT(
    'id', 'tts',
    'type', 'workflowNode',
    'position', JSON_OBJECT('x', 1800, 'y', 420),
    'data', JSON_OBJECT(
      'title', '角色配音',
      'nodeDefType', 'tts_model',
      'kind', 'model',
      'color', '#8b5cf6',
      'inputSlots', JSON_ARRAY(
        JSON_OBJECT('name', 'script', 'type', 'json', 'label', '剧本分镜')
      ),
      'outputSlots', JSON_ARRAY(
        JSON_OBJECT('name', 'audio', 'type', 'audio', 'label', '配音音频')
      ),
      'parameters', JSON_OBJECT(
        'modelConfigId', @comic_tts_model_id,
        'progressStep', '生成角色配音'
      )
    )
  )
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND @comic_tts_model_id IS NOT NULL
  AND JSON_SEARCH(CAST(w.nodes_json AS JSON), 'one', 'tts', NULL, '$[*].id') IS NULL;

-- 按节点能力重新绑定模型，避免把文本模型交给生图节点或把 MiniMax 当 SiliconFlow 调用。
UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.nodes_json = JSON_SET(
  CAST(w.nodes_json AS JSON),
  REPLACE(
    JSON_UNQUOTE(JSON_SEARCH(CAST(w.nodes_json AS JSON), 'one', 'script-planner', NULL, '$[*].id')),
    '.id',
    '.data.parameters.modelConfigId'
  ),
  @comic_text_model_id
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND @comic_text_model_id IS NOT NULL;

-- 历史节点内嵌 Prompt 只要求 title/synopsis/scenes，会覆盖 Worker 中统一维护的
-- 长剧本 screenplay 与分镜唯一性协议。移除旧 Prompt，避免两套输出契约互相冲突。
UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.nodes_json = JSON_REMOVE(
  CAST(w.nodes_json AS JSON),
  REPLACE(
    JSON_UNQUOTE(JSON_SEARCH(CAST(w.nodes_json AS JSON), 'one', 'script-planner', NULL, '$[*].id')),
    '.id',
    '.data.parameters.prompt'
  )
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND JSON_SEARCH(CAST(w.nodes_json AS JSON), 'one', 'script-planner', NULL, '$[*].id') IS NOT NULL;

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.nodes_json = JSON_SET(
  CAST(w.nodes_json AS JSON),
  REPLACE(
    JSON_UNQUOTE(JSON_SEARCH(CAST(w.nodes_json AS JSON), 'one', 'keyframe', NULL, '$[*].id')),
    '.id',
    '.data.parameters.modelConfigId'
  ),
  @comic_image_model_id
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND @comic_image_model_id IS NOT NULL;

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.nodes_json = JSON_SET(
  CAST(w.nodes_json AS JSON),
  REPLACE(
    JSON_UNQUOTE(JSON_SEARCH(CAST(w.nodes_json AS JSON), 'one', 'clip-video', NULL, '$[*].id')),
    '.id',
    '.data.parameters.modelConfigId'
  ),
  @comic_video_model_id
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND @comic_video_model_id IS NOT NULL;

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.nodes_json = JSON_SET(
  CAST(w.nodes_json AS JSON),
  REPLACE(
    JSON_UNQUOTE(JSON_SEARCH(CAST(w.nodes_json AS JSON), 'one', 'tts', NULL, '$[*].id')),
    '.id',
    '.data.parameters.modelConfigId'
  ),
  @comic_tts_model_id
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND @comic_tts_model_id IS NOT NULL;

-- 缺失配音边时补齐：剧本 -> TTS -> 合成。
UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.edges_json = JSON_ARRAY_APPEND(
  CAST(w.edges_json AS JSON),
  '$',
  JSON_OBJECT(
    'id', 'e-script-tts',
    'source', 'script-planner',
    'target', 'tts',
    'sourceHandle', 'out-script',
    'targetHandle', 'in-script',
    'type', 'smoothstep'
  )
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND JSON_SEARCH(CAST(w.edges_json AS JSON), 'one', 'tts', NULL, '$[*].target') IS NULL;

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.edges_json = JSON_ARRAY_APPEND(
  CAST(w.edges_json AS JSON),
  '$',
  JSON_OBJECT(
    'id', 'e-tts-compose',
    'source', 'tts',
    'target', 'compose',
    'sourceHandle', 'out-audio',
    'targetHandle', 'in-audio',
    'type', 'smoothstep'
  )
)
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND JSON_SEARCH(CAST(w.edges_json AS JSON), 'one', 'tts', NULL, '$[*].source') IS NULL;

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET
  w.config_json = JSON_SET(
    COALESCE(CAST(w.config_json AS JSON), JSON_OBJECT()),
    '$.requiredModelConfigCodes',
    JSON_ARRAY(
      'agnes-2.0-flash',
      'agnes-image-2.1-flash',
      'agnes-video-v2.0',
      'moma_cosyvoice'
    )
  ),
  w.version = w.version + 1,
  w.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND @comic_text_model_id IS NOT NULL
  AND @comic_image_model_id IS NOT NULL
  AND @comic_video_model_id IS NOT NULL
  AND @comic_tts_model_id IS NOT NULL;
