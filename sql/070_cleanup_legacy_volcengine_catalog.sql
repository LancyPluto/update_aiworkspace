-- Consolidate Volcengine/Doubao video & image catalog around gateway model configs.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE agent_model_configs
SET is_deleted = 1,
    enabled = 0,
    agent_enabled = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND config_code IN (
    'volcengine-seedance',
    'seedance_video_generation',
    'volcengine-seedream'
  )
  AND config_code NOT IN (
    'volcengine-gateway-video',
    'volcengine-gateway-image'
  );

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
  execution_task,
  extra_auth_json,
  console_url,
  balance_url,
  docs_url,
  enabled,
  agent_enabled,
  is_default
)
VALUES
(
  '火山 · 视频生成',
  'volcengine-gateway-video',
  'seedance',
  'doubao-seedance-1-5-pro-251215',
  'https://ark.cn-beijing.volces.com',
  '',
  900,
  'PER_CALL',
  0.87500000,
  JSON_ARRAY('VIDEO_GENERATION', 'DIGITAL_HUMAN'),
  'video_generation',
  '{"createPath":"/contents/generations/tasks","resultPath":"/contents/generations/tasks/{task_id}"}',
  'https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey',
  'https://console.volcengine.com/finance/overview',
  'https://www.volcengine.com/docs/82379/1520757',
  1,
  0,
  0
),
(
  '火山 · 图像生成',
  'volcengine-gateway-image',
  'volcengine_images',
  'doubao-seedream-4-5-251128',
  'https://ark.cn-beijing.volces.com',
  '',
  600,
  'PER_CALL',
  0.25000000,
  JSON_ARRAY('IMAGE_GENERATION'),
  'image_generation',
  '{"imageInputMode":"jsonImageArray","endpointPath":"/images/generations","responseFormat":"url","readTimeoutSeconds":600,"connectionRetries":2,"sslEofRetries":2}',
  'https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey',
  'https://console.volcengine.com/finance/overview',
  'https://www.volcengine.com/docs/82379/1541523',
  1,
  1,
  0
)
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  provider = VALUES(provider),
  model_name = VALUES(model_name),
  base_url = VALUES(base_url),
  timeout_seconds = VALUES(timeout_seconds),
  billing_unit = VALUES(billing_unit),
  unit_price = VALUES(unit_price),
  capabilities = VALUES(capabilities),
  execution_task = VALUES(execution_task),
  extra_auth_json = VALUES(extra_auth_json),
  console_url = VALUES(console_url),
  balance_url = VALUES(balance_url),
  docs_url = VALUES(docs_url),
  enabled = 1,
  is_deleted = 0,
  updated_at = CURRENT_TIMESTAMP;

UPDATE tool_workflow_versions
SET nodes_json = REPLACE(nodes_json, 'seedance_video_generation', 'volcengine-gateway-video'),
    edges_json = REPLACE(edges_json, 'seedance_video_generation', 'volcengine-gateway-video'),
    config_json = REPLACE(
      REPLACE(
        REPLACE(config_json, 'seedance_video_generation', 'volcengine-gateway-video'),
        '"modelConfigCode":"volcengine-seedance"',
        '"modelConfigCode":"volcengine-gateway-video"'
      ),
      'volcengine-seedream',
      'volcengine-gateway-image'
    )
WHERE nodes_json LIKE '%seedance_video_generation%'
   OR edges_json LIKE '%seedance_video_generation%'
   OR config_json LIKE '%seedance_video_generation%'
   OR config_json LIKE '%volcengine-seedance%'
   OR config_json LIKE '%volcengine-seedream%';

UPDATE ai_tools
SET model_config_id = (
      SELECT id FROM agent_model_configs WHERE config_code = 'volcengine-gateway-video' AND is_deleted = 0 LIMIT 1
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND model_config_id IN (
    SELECT id FROM agent_model_configs
    WHERE config_code IN ('seedance_video_generation', 'volcengine-seedance')
      AND is_deleted = 1
  );

UPDATE ai_tools
SET model_config_id = (
      SELECT id FROM agent_model_configs WHERE config_code = 'volcengine-gateway-image' AND is_deleted = 0 LIMIT 1
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND model_config_id IN (
    SELECT id FROM agent_model_configs
    WHERE config_code = 'volcengine-seedream'
      AND is_deleted = 1
  );

UPDATE ai_tools
SET is_deleted = 0,
    status = 'ONLINE',
    model_config_id = (SELECT id FROM agent_model_configs WHERE config_code = 'volcengine-gateway-video' AND is_deleted = 0 LIMIT 1),
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'volcengine-video';

UPDATE ai_tools
SET is_deleted = 0,
    status = 'ONLINE',
    model_config_id = (SELECT id FROM agent_model_configs WHERE config_code = 'volcengine-gateway-image' AND is_deleted = 0 LIMIT 1),
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'volcengine-image';
