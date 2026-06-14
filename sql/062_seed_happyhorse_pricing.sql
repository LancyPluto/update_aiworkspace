SET NAMES utf8mb4;

-- HappyHorse pricing aligned with Aliyun Model Studio China North 2 official rates.
-- Docs: https://help.aliyun.com/zh/model-studio/model-pricing
-- 720P: 0.9 CNY/s, 1080P: 1.6 CNY/s (output video, per second)
-- Video edit bills input + output seconds at the same rate.

UPDATE agent_model_configs
SET
  billing_unit = 'PER_SECOND',
  unit_price = 0.90000000,
  updated_at = CURRENT_TIMESTAMP
WHERE config_code IN (
  'happyhorse_t2v',
  'happyhorse_i2v',
  'happyhorse_r2v',
  'happyhorse_video_edit'
);

DELETE r FROM pricing_rules r
INNER JOIN agent_model_configs m ON r.scope_type = 'MODEL' AND r.scope_ref = m.id
WHERE m.config_code IN (
  'happyhorse_t2v',
  'happyhorse_i2v',
  'happyhorse_r2v',
  'happyhorse_video_edit'
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
  '1080P',
  CAST(16 AS DECIMAL(10,4)) / CAST(9 AS DECIMAL(10,4)),
  0,
  50,
  1,
  'HappyHorse 1080P 1.6 CNY/s vs 720P 0.9 CNY/s'
FROM agent_model_configs m
WHERE m.config_code IN (
  'happyhorse_t2v',
  'happyhorse_i2v',
  'happyhorse_r2v',
  'happyhorse_video_edit'
);

INSERT INTO pricing_rules (
  scope_type, scope_ref, param_key, rule_type, match_op, match_value,
  factor, extra_credits, priority, enabled, remark
)
SELECT
  'MODEL',
  m.id,
  'sourceVideo',
  'MULTIPLIER',
  'ANY',
  NULL,
  2.0000,
  0,
  40,
  1,
  'Video edit bills input+output seconds, estimate x2 when source video present'
FROM agent_model_configs m
WHERE m.config_code = 'happyhorse_video_edit';

UPDATE ai_tools t
JOIN agent_model_configs m ON t.model_config_id = m.id
SET
  t.estimated_credit_cost = CASE m.config_code
    WHEN 'happyhorse_video_edit' THEN 1080
    ELSE 540
  END,
  t.updated_at = CURRENT_TIMESTAMP
WHERE m.config_code IN (
  'happyhorse_t2v',
  'happyhorse_i2v',
  'happyhorse_r2v',
  'happyhorse_video_edit'
);
