SET NAMES utf8mb4;

-- Agnes AI (Sapiens AI) 厂商登记。045 已含该行，但部分环境在 045 应用后才追加 agnes，
-- 迁移日志已记 045 不会重跑，故此处幂等补种，保证 model_vendors 含 agnes。
INSERT INTO model_vendors(vendor_code, vendor_label, icon_asset, sort_order, enabled)
VALUES ('agnes', 'Agnes AI', 'api', 80, 1)
ON DUPLICATE KEY UPDATE
  vendor_label = VALUES(vendor_label),
  icon_asset = VALUES(icon_asset),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  updated_at = CURRENT_TIMESTAMP;
