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
  NULL,
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
