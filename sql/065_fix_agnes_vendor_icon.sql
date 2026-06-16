SET NAMES utf8mb4;

UPDATE model_vendors
SET icon_asset = 'agnes',
    updated_at = CURRENT_TIMESTAMP
WHERE vendor_code = 'agnes'
  AND icon_asset <> 'agnes';
