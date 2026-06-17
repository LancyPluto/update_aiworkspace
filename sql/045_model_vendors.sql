SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS model_vendors (
  vendor_code VARCHAR(64) PRIMARY KEY COMMENT '厂商编码，如 deepseek、kling、openai_gateway',
  vendor_label VARCHAR(128) NOT NULL COMMENT '展示名称（中文优先）',
  icon_asset VARCHAR(128) NOT NULL COMMENT '前端静态资源名（不含扩展名）',
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_model_vendors_enabled (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO model_vendors(vendor_code, vendor_label, icon_asset, sort_order, enabled)
VALUES
  ('kling', '可灵', 'kling', 10, 1),
  ('volcengine', '火山引擎 / 豆包', 'doubao', 20, 1),
  ('siliconflow', 'SiliconFlow', 'siliconflow', 30, 1),
  ('deepseek', 'DeepSeek', 'deepseek', 40, 1),
  ('minimax', 'MiniMax', 'minimax', 50, 1),
  ('suno', 'Suno', 'suno', 55, 1),
  ('openai', 'OpenAI', 'openai', 60, 1),
  ('openai_gateway', 'OpenAI 兼容网关', 'openrouter', 70, 1),
  ('agnes', 'Agnes AI', 'agnes', 80, 1),
  ('infinitetalk', 'InfiniteTalk', 'infinitetalk', 90, 1),
  ('mock', 'Mock', 'api', 999, 1)
ON DUPLICATE KEY UPDATE
  vendor_label = VALUES(vendor_label),
  icon_asset = VALUES(icon_asset),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  updated_at = CURRENT_TIMESTAMP;

