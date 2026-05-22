SET NAMES utf8mb4;

INSERT INTO agent_model_configs (
  display_name,
  config_code,
  provider,
  model_name,
  base_url,
  api_key,
  timeout_seconds,
  billing_unit,
  unit_price,
  capabilities,
  enabled,
  is_default
)
VALUES (
  'InfiniteTalk audio-driven digital human',
  'infinitetalk_video_avatar',
  'infinitetalk',
  'MeiGen-AI/InfiniteTalk',
  'http://host.docker.internal:7860',
  '',
  1800,
  'PER_CALL',
  0.00000000,
  JSON_ARRAY('VIDEO_GENERATION', 'DIGITAL_HUMAN'),
  1,
  0
)
ON DUPLICATE KEY UPDATE
  provider = VALUES(provider),
  model_name = VALUES(model_name),
  base_url = VALUES(base_url),
  timeout_seconds = VALUES(timeout_seconds),
  billing_unit = VALUES(billing_unit),
  unit_price = VALUES(unit_price),
  capabilities = VALUES(capabilities),
  enabled = 1,
  updated_at = CURRENT_TIMESTAMP;

UPDATE ai_tools
SET model_config_id = (
      SELECT id
      FROM agent_model_configs
      WHERE config_code = 'infinitetalk_video_avatar'
      LIMIT 1
    ),
    execution_handler = 'DIGITAL_HUMAN',
    output_modality = 'VIDEO',
    estimated_credit_cost = 3
WHERE tool_code = 'digital_human_agent';

UPDATE tool_workflows wf
JOIN ai_tools t ON t.id = wf.tool_id
SET wf.config_json = JSON_SET(
      wf.config_json,
      '$.referenceProject',
      'MeiGen-AI/InfiniteTalk',
      '$.audioVideoSyncStrategy',
      'infinitetalk_audio_driven_image_or_video_to_video',
      '$.requiredModelConfigCodes',
      JSON_ARRAY('siliconflow_voice_tts', 'siliconflow_image_turbo', 'infinitetalk_video_avatar')
    )
WHERE t.tool_code = 'digital_human_agent'
  AND JSON_VALID(wf.config_json);

UPDATE tool_workflows wf
JOIN ai_tools t ON t.id = wf.tool_id
SET wf.nodes_json = JSON_SET(
      wf.nodes_json,
      '$[4].data.subtitle',
      'InfiniteTalk 音频驱动数字人视频',
      '$[4].data.detail',
      '使用参考图或参考视频与口播音频生成唇形同步、动作稳定的数字人成片',
      '$[4].data.parameters.modelConfigId',
      (SELECT id FROM agent_model_configs WHERE config_code = 'infinitetalk_video_avatar' LIMIT 1),
      '$[4].data.parameters.syncMode',
      'infinitetalk_audio_driven'
    )
WHERE t.tool_code = 'digital_human_agent'
  AND JSON_VALID(wf.nodes_json);
