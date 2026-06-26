SET NAMES utf8mb4;

-- Vidu (Shengshu) vendor migration
INSERT INTO model_vendors(vendor_code, vendor_label, icon_asset, sort_order, enabled)
VALUES
  ('vidu', 'Vidu (生数科技)', 'vidu', 65, 1)
ON DUPLICATE KEY UPDATE
  vendor_label = VALUES(vendor_label),
  icon_asset = VALUES(icon_asset),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  updated_at = CURRENT_TIMESTAMP;
