SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Migration 116 already supplies the official READY contract. Keep that
-- administrator-owned contract intact and only expose the real model identity
-- for existing Seedance 1.5 rows in the tool binding UI.
UPDATE agent_model_configs model
SET model.display_name = 'Seedance 1.5 Pro（火山方舟）',
    model.updated_at = CURRENT_TIMESTAMP
WHERE model.is_deleted = 0
  AND (
    model.config_code = 'volcengine-gateway-video'
    OR (
      LOWER(TRIM(model.provider)) = 'seedance'
      AND model.model_name = 'doubao-seedance-1-5-pro-251215'
    )
  )
  AND COALESCE(model.display_name, '') <> 'Seedance 1.5 Pro（火山方舟）';
