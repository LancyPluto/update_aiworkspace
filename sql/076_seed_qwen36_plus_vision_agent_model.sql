-- Seed Alibaba Cloud Bailian qwen3.6-plus as an Agent vision-capable chat model.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS model_provider_metadata (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_code VARCHAR(64) NOT NULL UNIQUE,
  label VARCHAR(128) NOT NULL,
  capabilities_json TEXT NOT NULL,
  default_base_url VARCHAR(512) NULL,
  default_model VARCHAR(128) NULL,
  billing_default VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
  provider_protocol VARCHAR(64) NULL,
  vendor_kind VARCHAR(64) NULL,
  upstream_vendor VARCHAR(64) NULL,
  test_strategy VARCHAR(32) NOT NULL DEFAULT 'accept_only',
  worker_ready TINYINT NOT NULL DEFAULT 0,
  adapter_installed TINYINT NOT NULL DEFAULT 0,
  adapter_key VARCHAR(64) NULL,
  metadata_version VARCHAR(64) NOT NULL DEFAULT 'db',
  auth_schema_json TEXT NULL,
  model_param_schema_json TEXT NULL,
  description TEXT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO model_vendors(vendor_code, vendor_label, icon_asset, sort_order, enabled)
VALUES ('qwen', '阿里云百炼', 'qwen', 45, 1)
ON DUPLICATE KEY UPDATE
  vendor_label = VALUES(vendor_label),
  icon_asset = VALUES(icon_asset),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  updated_at = NOW();

INSERT INTO model_provider_metadata(
  provider_code,
  label,
  capabilities_json,
  default_base_url,
  default_model,
  billing_default,
  provider_protocol,
  vendor_kind,
  upstream_vendor,
  test_strategy,
  worker_ready,
  adapter_installed,
  adapter_key,
  metadata_version,
  auth_schema_json,
  model_param_schema_json,
  description,
  enabled
)
VALUES (
  'qwen',
  'Alibaba Cloud Bailian Qwen',
  '["TEXT_GENERATION","VISION_INPUT"]',
  'https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1',
  'qwen3.6-plus',
  'TOKEN_PER_M',
  'openai_chat',
  'direct',
  'aliyun_bailian',
  'agent_service',
  1,
  1,
  'qwen',
  'qwen_bailian.v1',
  '{"fields":[{"key":"apiKey","label":"DASHSCOPE_API_KEY","type":"password","required":true},{"key":"baseUrl","label":"Base URL","type":"url","required":true,"placeholder":"https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"}]}',
  '{"models":["qwen3.6-plus"],"fields":[{"key":"modelName","label":"模型名称","type":"text","required":true},{"key":"capabilities","label":"能力","type":"multi_select","options":["TEXT_GENERATION","VISION_INPUT"],"required":true}]}',
  'Alibaba Cloud Model Studio Bailian Qwen OpenAI-compatible Chat Completions endpoint. qwen3.6-plus is seeded for Agent image understanding through VISION_INPUT.',
  1
)
ON DUPLICATE KEY UPDATE
  label = VALUES(label),
  capabilities_json = VALUES(capabilities_json),
  default_base_url = VALUES(default_base_url),
  default_model = VALUES(default_model),
  billing_default = VALUES(billing_default),
  provider_protocol = VALUES(provider_protocol),
  vendor_kind = VALUES(vendor_kind),
  upstream_vendor = VALUES(upstream_vendor),
  test_strategy = VALUES(test_strategy),
  worker_ready = VALUES(worker_ready),
  adapter_installed = VALUES(adapter_installed),
  adapter_key = VALUES(adapter_key),
  metadata_version = VALUES(metadata_version),
  auth_schema_json = VALUES(auth_schema_json),
  model_param_schema_json = VALUES(model_param_schema_json),
  description = VALUES(description),
  enabled = VALUES(enabled),
  updated_at = NOW();

INSERT INTO agent_model_configs (
  vendor_account_id,
  display_name,
  config_code,
  provider,
  model_name,
  base_url,
  api_key,
  docs_url,
  timeout_seconds,
  input_token_price_per_1k,
  output_token_price_per_1k,
  input_token_price_per_1m,
  output_token_price_per_1m,
  billing_unit,
  unit_price,
  capabilities,
  enabled,
  agent_enabled,
  is_default,
  is_deleted
)
SELECT
  a.id,
  'Qwen3.6 Plus 视觉 Agent',
  'qwen3_6_plus_vision_agent',
  'qwen',
  'qwen3.6-plus',
  CASE
    WHEN a.base_url IS NULL OR a.base_url = '' THEN NULL
    WHEN LOWER(a.base_url) LIKE '%compatible-mode/v1%' THEN NULL
    ELSE 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  END,
  '',
  'https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope',
  120,
  0,
  0,
  0,
  0,
  'TOKEN_PER_M',
  0,
  '["TEXT_GENERATION","VISION_INPUT"]',
  CASE WHEN a.id IS NULL THEN 0 ELSE 1 END,
  1,
  0,
  0
FROM (SELECT 1) seed
LEFT JOIN (
  SELECT id, base_url
  FROM model_vendor_accounts
  WHERE (
      vendor_code IN ('qwen', 'dashscope', 'bailian_happyhorse')
      OR LOWER(COALESCE(base_url, '')) LIKE '%dashscope.aliyuncs.com%'
      OR LOWER(COALESCE(base_url, '')) LIKE '%maas.aliyuncs.com%'
    )
    AND COALESCE(is_deleted, 0) = 0
  ORDER BY
    CASE WHEN health_status = 'OK' THEN 0 ELSE 1 END,
    enabled DESC,
    CASE WHEN api_key IS NULL OR api_key = '' THEN 1 ELSE 0 END,
    id DESC
  LIMIT 1
) a ON 1 = 1
WHERE NOT EXISTS (
  SELECT 1
  FROM agent_model_configs
  WHERE config_code = 'qwen3_6_plus_vision_agent'
    AND COALESCE(is_deleted, 0) = 0
);

UPDATE agent_model_configs m
LEFT JOIN (
  SELECT id, base_url
  FROM model_vendor_accounts
  WHERE (
      vendor_code IN ('qwen', 'dashscope', 'bailian_happyhorse')
      OR LOWER(COALESCE(base_url, '')) LIKE '%dashscope.aliyuncs.com%'
      OR LOWER(COALESCE(base_url, '')) LIKE '%maas.aliyuncs.com%'
    )
    AND COALESCE(is_deleted, 0) = 0
  ORDER BY
    CASE WHEN health_status = 'OK' THEN 0 ELSE 1 END,
    enabled DESC,
    CASE WHEN api_key IS NULL OR api_key = '' THEN 1 ELSE 0 END,
    id DESC
  LIMIT 1
) a ON 1 = 1
SET
  m.provider = 'qwen',
  m.model_name = 'qwen3.6-plus',
  m.display_name = 'Qwen3.6 Plus 视觉 Agent',
  m.vendor_account_id = COALESCE(m.vendor_account_id, a.id),
  m.base_url = CASE
    WHEN m.base_url IS NOT NULL AND m.base_url <> '' THEN m.base_url
    WHEN a.base_url IS NULL OR a.base_url = '' THEN m.base_url
    WHEN LOWER(a.base_url) LIKE '%compatible-mode/v1%' THEN m.base_url
    ELSE 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  END,
  m.capabilities = '["TEXT_GENERATION","VISION_INPUT"]',
  m.agent_enabled = 1,
  m.enabled = CASE WHEN a.id IS NULL AND (m.api_key IS NULL OR m.api_key = '') THEN m.enabled ELSE 1 END,
  m.billing_unit = 'TOKEN_PER_M',
  m.docs_url = COALESCE(NULLIF(m.docs_url, ''), 'https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope'),
  m.updated_at = NOW()
WHERE m.config_code = 'qwen3_6_plus_vision_agent'
  AND COALESCE(m.is_deleted, 0) = 0;
