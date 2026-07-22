SET NAMES utf8mb4;

UPDATE agent_model_configs
SET enabled = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE COALESCE(is_deleted, 0) = 0
  AND COALESCE(enabled, 0) <> 1;
