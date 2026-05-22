SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tool_workflows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  workflow_name VARCHAR(128) NOT NULL DEFAULT 'default',
  nodes_json MEDIUMTEXT NOT NULL COMMENT 'ReactFlow nodes as JSON',
  edges_json MEDIUMTEXT NOT NULL COMMENT 'ReactFlow edges as JSON',
  groups_json MEDIUMTEXT COMMENT 'Visual groups as JSON',
  config_json MEDIUMTEXT COMMENT 'Workflow-level config',
  version INT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  updated_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_workflow (tool_id, workflow_name),
  KEY idx_workflow_tool_status (tool_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_workflow_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workflow_id BIGINT NOT NULL,
  version INT NOT NULL,
  nodes_json MEDIUMTEXT NOT NULL,
  edges_json MEDIUMTEXT NOT NULL,
  groups_json MEDIUMTEXT,
  config_json MEDIUMTEXT,
  snapshot_label VARCHAR(255),
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_version (workflow_id, version),
  KEY idx_workflow_ver_created (workflow_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
      'position', JSON_OBJECT('x', 40, 'y', 210),
      'data', JSON_OBJECT(
        'title', 'Start',
        'subtitle', '用户提交表单',
        'detail', '接收用户端表单、会话上下文和素材输入',
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
      'position', JSON_OBJECT('x', 340, 'y', 210),
      'data', JSON_OBJECT(
        'title', 'Field Input',
        'subtitle', '用户端表单字段',
        'detail', '配置用户侧展示的输入框、下拉框、选项和必填规则',
        'kind', 'input',
        'nodeDefType', 'field_input',
        'iconName', 'file-input',
        'color', '#64748b',
        'parameters', JSON_OBJECT()
      )
    ),
    JSON_OBJECT(
      'id', 'tts',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 650, 'y', 60),
      'data', JSON_OBJECT(
        'title', 'TTS Voice',
        'subtitle', 'CosyVoice2 生成口播音频',
        'detail', '根据口播脚本和角色性别选择一致的音色',
        'kind', 'model',
        'nodeDefType', 'tts_model',
        'iconName', 'volume-2',
        'color', '#8b5cf6',
        'parameters', JSON_OBJECT(
          'modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'siliconflow_voice_tts' LIMIT 1),
          'voicePolicy', 'match_avatar_gender'
        )
      )
    ),
    JSON_OBJECT(
      'id', 'image',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 650, 'y', 340),
      'data', JSON_OBJECT(
        'title', 'Image Generation',
        'subtitle', 'Z-Image Turbo 生成数字人形象和背景',
        'detail', '根据形象、场景、画面比例生成参考图',
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
      'id', 'video',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 980, 'y', 210),
      'data', JSON_OBJECT(
        'title', 'Audio-driven Video',
        'subtitle', 'Seedance 图生视频，参考 InfiniteTalk 的音频驱动思路',
        'detail', '视频生成必须接收参考图、口播音频、时长、清晰度和画面比例',
        'kind', 'model',
        'nodeDefType', 'video_model',
        'iconName', 'video',
        'color', '#f97316',
        'parameters', JSON_OBJECT(
          'modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'seedance_video_generation' LIMIT 1),
          'resolution', '480p',
          'durationSeconds', 5,
          'aspectRatio', '16:9',
          'syncMode', 'audio_driven_lip_sync'
        )
      )
    ),
    JSON_OBJECT(
      'id', 'subtitle',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 1310, 'y', 210),
      'data', JSON_OBJECT(
        'title', 'Subtitles + FFmpeg',
        'subtitle', '按语音时长生成字幕并烧录',
        'detail', '使用音频时长切分字幕，FFmpeg 对齐视频、音频和字幕输出 final.mp4',
        'kind', 'tool',
        'nodeDefType', 'subtitle',
        'iconName', 'captions',
        'color', '#f97316',
        'parameters', JSON_OBJECT(
          'asrModelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'siliconflow_asr_teleai' LIMIT 1),
          'alignmentPolicy', 'audio_duration_first'
        )
      )
    ),
    JSON_OBJECT(
      'id', 'output',
      'type', 'workflowNode',
      'position', JSON_OBJECT('x', 1620, 'y', 210),
      'data', JSON_OBJECT(
        'title', 'End',
        'subtitle', '渲染视频、字幕文件和下载入口',
        'detail', '用户侧展示可播放视频和下载按钮',
        'kind', 'output',
        'nodeDefType', 'video_output',
        'iconName', 'video',
        'color', '#ef4444',
        'parameters', JSON_OBJECT()
      )
    )
  ),
  JSON_ARRAY(
    JSON_OBJECT('id', 'e-start-field', 'source', 'start', 'sourceHandle', 'out-output', 'target', 'field-input', 'type', 'smoothstep', 'markerEnd', JSON_OBJECT('type', 'arrowclosed', 'color', '#94a3b8'), 'style', JSON_OBJECT('stroke', '#94a3b8', 'strokeWidth', 2)),
    JSON_OBJECT('id', 'e-field-tts', 'source', 'field-input', 'sourceHandle', 'out-params', 'target', 'tts', 'targetHandle', 'in-text', 'type', 'smoothstep', 'markerEnd', JSON_OBJECT('type', 'arrowclosed', 'color', '#94a3b8'), 'style', JSON_OBJECT('stroke', '#94a3b8', 'strokeWidth', 2)),
    JSON_OBJECT('id', 'e-field-image', 'source', 'field-input', 'sourceHandle', 'out-params', 'target', 'image', 'targetHandle', 'in-prompt', 'type', 'smoothstep', 'markerEnd', JSON_OBJECT('type', 'arrowclosed', 'color', '#94a3b8'), 'style', JSON_OBJECT('stroke', '#94a3b8', 'strokeWidth', 2)),
    JSON_OBJECT('id', 'e-tts-video', 'source', 'tts', 'sourceHandle', 'out-audio', 'target', 'video', 'targetHandle', 'in-audio', 'type', 'smoothstep', 'markerEnd', JSON_OBJECT('type', 'arrowclosed', 'color', '#94a3b8'), 'style', JSON_OBJECT('stroke', '#94a3b8', 'strokeWidth', 2)),
    JSON_OBJECT('id', 'e-image-video', 'source', 'image', 'sourceHandle', 'out-image', 'target', 'video', 'targetHandle', 'in-image', 'type', 'smoothstep', 'markerEnd', JSON_OBJECT('type', 'arrowclosed', 'color', '#94a3b8'), 'style', JSON_OBJECT('stroke', '#94a3b8', 'strokeWidth', 2)),
    JSON_OBJECT('id', 'e-video-subtitle', 'source', 'video', 'sourceHandle', 'out-video', 'target', 'subtitle', 'targetHandle', 'in-video', 'type', 'smoothstep', 'markerEnd', JSON_OBJECT('type', 'arrowclosed', 'color', '#94a3b8'), 'style', JSON_OBJECT('stroke', '#94a3b8', 'strokeWidth', 2)),
    JSON_OBJECT('id', 'e-subtitle-output', 'source', 'subtitle', 'sourceHandle', 'out-video', 'target', 'output', 'targetHandle', 'in-video', 'type', 'smoothstep', 'markerEnd', JSON_OBJECT('type', 'arrowclosed', 'color', '#94a3b8'), 'style', JSON_OBJECT('stroke', '#94a3b8', 'strokeWidth', 2))
  ),
  NULL,
  JSON_OBJECT(
    'requiredModelConfigCodes',
    JSON_ARRAY('siliconflow_voice_tts', 'siliconflow_image_turbo', 'seedance_video_generation', 'siliconflow_asr_teleai'),
    'audioVideoSyncStrategy',
    'audio_driven_reference_image_to_video_then_ffmpeg_mux',
    'referenceProject',
    'MeiGen-AI/InfiniteTalk'
  ),
  1,
  'DRAFT',
  NULL,
  NULL
FROM ai_tools t
WHERE t.tool_code IN ('digital_human_agent', 'ai_comic_drama_agent')
ON DUPLICATE KEY UPDATE
  config_json = VALUES(config_json);
