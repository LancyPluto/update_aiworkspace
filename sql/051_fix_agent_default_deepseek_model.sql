-- Fix invalid DeepSeek default model name used by agent routing.
-- deepseek-v4-pro is not a valid DeepSeek API model id; map default to deepseek-chat.
SET NAMES utf8mb4;

UPDATE agent_model_configs
SET model_name = 'deepseek-chat',
    updated_at = NOW()
WHERE model_name = 'deepseek-v4-pro'
  AND is_deleted = 0;

-- Prefer an explicitly configured deepseek-chat row as agent default when present.
UPDATE agent_model_configs
SET is_default = 0,
    updated_at = NOW()
WHERE is_default = 1
  AND is_deleted = 0
  AND model_name <> 'deepseek-chat';

UPDATE agent_model_configs
SET enabled = 1,
    agent_enabled = 1,
    is_default = 1,
    updated_at = NOW()
WHERE is_deleted = 0
  AND model_name = 'deepseek-chat'
  AND provider IN ('deepseek', 'deepseek_compatible')
ORDER BY id ASC
LIMIT 1;
