SET NAMES utf8mb4;

-- GPT Image 2 quality multipliers aligned with OpenAI official per-image pricing (1024x1024).
-- Low $0.006, Medium $0.053, High $0.211 per image.
-- https://openai.com/api/pricing/
-- Baseline billing stays IMAGE_TOKEN; rules scale vendor cost by quality param.

DELETE r FROM pricing_rules r
INNER JOIN agent_model_configs m ON r.scope_type = 'MODEL' AND r.scope_ref = m.id
WHERE m.config_code IN ('9', '10')
   OR LOWER(m.model_name) LIKE '%gpt-image-2%';

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'quality',
  'MULTIPLIER',
  'EQ',
  'medium',
  CAST(53 AS DECIMAL(10,4)) / CAST(6 AS DECIMAL(10,4)),
  0,
  50,
  1,
  'GPT Image 2 medium $0.053 vs low $0.006 (1024x1024)'
FROM agent_model_configs m
WHERE m.config_code IN ('9', '10')
   OR LOWER(m.model_name) LIKE '%gpt-image-2%';

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'quality',
  'MULTIPLIER',
  'EQ',
  'high',
  CAST(211 AS DECIMAL(10,4)) / CAST(6 AS DECIMAL(10,4)),
  0,
  51,
  1,
  'GPT Image 2 high $0.211 vs low $0.006 (1024x1024)'
FROM agent_model_configs m
WHERE m.config_code IN ('9', '10')
   OR LOWER(m.model_name) LIKE '%gpt-image-2%';

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
  '按生成数量 count 倍率（参数值即倍数）'
FROM agent_model_configs m
WHERE m.config_code IN ('9', '10')
   OR LOWER(m.model_name) LIKE '%gpt-image-2%';
