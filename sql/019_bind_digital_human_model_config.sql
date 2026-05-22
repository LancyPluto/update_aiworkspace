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
  'SiliconFlow image and speech for digital human',
  'siliconflow_digital_human',
  'siliconflow_images',
  'Tongyi-MAI/Z-Image-Turbo',
  'https://api.siliconflow.cn',
  'sk-qzxseslflrfnxtgzxaeapgzyabzlwlvvzwapjihaejjahquj',
  600,
  'PER_CALL',
  0.00000000,
  JSON_ARRAY('IMAGE_GENERATION', 'DIGITAL_HUMAN'),
  1,
  0
)
ON DUPLICATE KEY UPDATE
  provider = VALUES(provider),
  model_name = VALUES(model_name),
  base_url = VALUES(base_url),
  api_key = VALUES(api_key),
  capabilities = VALUES(capabilities),
  enabled = 1,
  is_default = 0,
  updated_at = CURRENT_TIMESTAMP;

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
VALUES
(
  'SiliconFlow TTS - CosyVoice2',
  'siliconflow_voice_tts',
  'siliconflow_speech',
  'FunAudioLLM/CosyVoice2-0.5B',
  'https://api.siliconflow.cn',
  'sk-qzxseslflrfnxtgzxaeapgzyabzlwlvvzwapjihaejjahquj',
  300,
  'PER_CALL',
  0.00000000,
  JSON_ARRAY('TEXT_TO_SPEECH'),
  1,
  0
),
(
  'SiliconFlow ASR - TeleSpeech',
  'siliconflow_asr_teleai',
  'siliconflow_asr',
  'TeleAI/TeleSpeechASR',
  'https://api.siliconflow.cn',
  'sk-qzxseslflrfnxtgzxaeapgzyabzlwlvvzwapjihaejjahquj',
  300,
  'PER_CALL',
  0.00000000,
  JSON_ARRAY('SPEECH_TO_TEXT'),
  1,
  0
),
(
  'SiliconFlow Image - Z-Image Turbo',
  'siliconflow_image_turbo',
  'siliconflow_images',
  'Tongyi-MAI/Z-Image-Turbo',
  'https://api.siliconflow.cn',
  'sk-qzxseslflrfnxtgzxaeapgzyabzlwlvvzwapjihaejjahquj',
  600,
  'PER_CALL',
  0.00000000,
  JSON_ARRAY('IMAGE_GENERATION', 'DIGITAL_HUMAN'),
  1,
  0
),
(
  'Seedance video - Doubao Seedance',
  'seedance_video_generation',
  'seedance',
  'doubao-seedance-1-5-pro-251215',
  'https://ark.cn-beijing.volces.com',
  'ark-b2a000fc-9c66-480d-a28f-4de1d07b90ec-e7e75',
  900,
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
  api_key = VALUES(api_key),
  timeout_seconds = VALUES(timeout_seconds),
  billing_unit = VALUES(billing_unit),
  unit_price = VALUES(unit_price),
  capabilities = VALUES(capabilities),
  enabled = 1,
  is_default = 0,
  updated_at = CURRENT_TIMESTAMP;

UPDATE ai_tools
SET model_config_id = (
      SELECT id
      FROM agent_model_configs
      WHERE config_code = 'siliconflow_digital_human'
      LIMIT 1
    ),
    execution_handler = 'DIGITAL_HUMAN',
    output_modality = 'VIDEO',
    estimated_credit_cost = 3
WHERE tool_code IN ('digital_human_agent', 'ai_comic_drama_agent');
