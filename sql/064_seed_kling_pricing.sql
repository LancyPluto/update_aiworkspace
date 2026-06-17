SET NAMES utf8mb4;

-- Kling gateway pricing: PER_SECOND for video gateways (std 0.6 CNY/s baseline = 3 CNY / 5s).
-- duration is applied automatically by PricingServiceImpl for PER_SECOND billing.

UPDATE agent_model_configs
SET
  billing_unit = 'PER_SECOND',
  unit_price = 0.60000000,
  updated_at = CURRENT_TIMESTAMP
WHERE config_code IN (
  'kling-gateway-text-to-video',
  'kling-gateway-image-to-video',
  'kling-gateway-motion-control',
  'kling-gateway-multi-image-to-video',
  'kling-gateway-omni-video'
);

UPDATE agent_model_configs
SET
  billing_unit = 'PER_CALL',
  unit_price = 0.01999800,
  updated_at = CURRENT_TIMESTAMP
WHERE config_code IN (
  'kling-gateway-image-generation',
  'kling-gateway-omni-image'
);

DELETE r FROM pricing_rules r
INNER JOIN agent_model_configs m ON r.scope_type = 'MODEL' AND r.scope_ref = m.id
WHERE m.config_code LIKE 'kling-gateway-%';

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'mode',
  'MULTIPLIER',
  'EQ',
  'pro',
  1.5000,
  0,
  50,
  1,
  'Kling pro vs std'
FROM agent_model_configs m
WHERE m.config_code IN (
  'kling-gateway-text-to-video',
  'kling-gateway-image-to-video',
  'kling-gateway-motion-control',
  'kling-gateway-multi-image-to-video',
  'kling-gateway-omni-video'
);

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'sound',
  'MULTIPLIER',
  'EQ',
  'on',
  1.2000,
  0,
  51,
  1,
  'Kling sound on'
FROM agent_model_configs m
WHERE m.config_code IN (
  'kling-gateway-text-to-video',
  'kling-gateway-image-to-video',
  'kling-gateway-multi-image-to-video',
  'kling-gateway-omni-video'
);

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'model',
  'MULTIPLIER',
  'EQ',
  'kling-v2-5-turbo',
  0.8000,
  0,
  60,
  1,
  'Kling Turbo discount example'
FROM agent_model_configs m
WHERE m.config_code IN (
  'kling-gateway-image-to-video',
  'kling-gateway-text-to-video'
);

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'count',
  'MULTIPLIER',
  'VALUE',
  NULL,
  1.0000,
  0,
  40,
  1,
  'Kling image generation count multiplier'
FROM agent_model_configs m
WHERE m.config_code IN (
  'kling-gateway-image-generation',
  'kling-gateway-omni-image'
);

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'resolution',
  'MULTIPLIER',
  'EQ',
  '2k',
  1.5000,
  0,
  50,
  1,
  'Kling Omni image 2K vs 1K'
FROM agent_model_configs m
WHERE m.config_code = 'kling-gateway-omni-image';

UPDATE ai_tools t
JOIN agent_model_configs m ON t.model_config_id = m.id
SET
  t.estimated_credit_cost = CASE
    WHEN m.config_code IN ('kling-gateway-image-generation', 'kling-gateway-omni-image') THEN 3
    ELSE 360
  END,
  t.updated_at = CURRENT_TIMESTAMP
WHERE m.config_code LIKE 'kling-gateway-%';
