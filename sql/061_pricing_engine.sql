SET NAMES utf8mb4;

-- 计费引擎：定价规则 + 利润率(加价/保底) 配置，统一驱动「参数→算力」计算。
-- 默认数据保证与旧逻辑等价：全局加价 1.20，无保底，无参数规则。

CREATE TABLE IF NOT EXISTS pricing_margins (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',   -- GLOBAL / CATEGORY / MODEL
  scope_ref BIGINT NOT NULL DEFAULT 0,                 -- categoryId 或 modelConfigId；GLOBAL 固定为 0
  markup_ratio DECIMAL(10,4) NOT NULL DEFAULT 1.2000,  -- 加价倍率（含成本，1.20 = 加价 20%）
  min_credits INT NOT NULL DEFAULT 0,                  -- 保底价（最低收取算力，防止低价单亏损）
  image_estimate_input_tokens INT NULL,                -- IMAGE_TOKEN 预估输入 token；空值继承上级或默认 8000
  image_estimate_output_tokens INT NULL,               -- IMAGE_TOKEN 预估输出 token；空值继承上级或默认 8000
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pricing_margin_scope (scope_type, scope_ref),
  KEY idx_pricing_margin_lookup (scope_type, scope_ref, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pricing_rules (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type VARCHAR(16) NOT NULL DEFAULT 'MODEL',    -- MODEL / TOOL / CATEGORY
  scope_ref BIGINT NOT NULL DEFAULT 0,                 -- modelConfigId / toolId / categoryId
  param_key VARCHAR(64) NOT NULL,                      -- duration / quality / count / resolution / steps ...
  rule_type VARCHAR(16) NOT NULL DEFAULT 'MULTIPLIER', -- MULTIPLIER / TIER / ADDITIVE
  match_op VARCHAR(8) NOT NULL DEFAULT 'EQ',           -- ANY / EQ / GT / GTE / LT / LTE
  match_value VARCHAR(64),                             -- 比较值（EQ 用字符串，比较类用数字）
  factor DECIMAL(10,4) NOT NULL DEFAULT 1.0000,        -- 倍率（MULTIPLIER / TIER）
  extra_credits INT NOT NULL DEFAULT 0,                -- 附加算力（ADDITIVE，命中即叠加）
  priority INT NOT NULL DEFAULT 100,                   -- 越小越先应用
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_pricing_rules_scope (scope_type, scope_ref, enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO pricing_margins (scope_type, scope_ref, markup_ratio, min_credits, enabled, remark)
SELECT 'GLOBAL', 0, 1.2000, 0, 1, '默认全局加价 20%'
WHERE NOT EXISTS (SELECT 1 FROM pricing_margins WHERE scope_type = 'GLOBAL' AND scope_ref = 0);

-- 双轨记账对齐：在用量审计中显式落「厂商成本 / 含加价收费 / 利润 / 加价快照」。
ALTER TABLE billing_usage_logs
  ADD COLUMN vendor_cost_amount DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER cost_amount,
  ADD COLUMN customer_charge_credits INT NOT NULL DEFAULT 0 AFTER charged_credits,
  ADD COLUMN margin_credits INT NOT NULL DEFAULT 0 AFTER customer_charge_credits,
  ADD COLUMN markup_ratio DECIMAL(10,4) NOT NULL DEFAULT 0 AFTER margin_credits;
