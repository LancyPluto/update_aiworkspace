-- Keep Kling catalog consolidated around the seven gateway model configs.
-- This is intentionally explicit because generic config-bundle import keeps
-- stale model configs when they still inherit valid vendor-account credentials.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE ai_tools
SET is_deleted = 1,
    status = 'OFFLINE',
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND tool_code IN (
    'v2_1',
    'tool',
    'kling_image_to_video',
    'kling-v3-omni',
    'kling-video-o1-omni',
    'kling-v3-text-to-video',
    'kling-v3-image-to-video',
    'kling-v3-multi-image-reference',
    'kling-v3-motion-control',
    'kling-v2-6-image-to-video',
    'kling-v2-6-motion-control',
    'kling-v2-5-turbo-image-to-video',
    'kling-v2-1-master-image-to-video',
    'kling-v2-1-image-to-video',
    'kling-v2-master-image-to-video',
    'kling-v1-6-image-to-video',
    'kling-v1-5-image-to-video',
    'kling-v1-image-to-video',
    'kling-image-generation-v3',
    'kling-image-generation-v2-1',
    'kling-image-generation-v1'
  );

UPDATE agent_model_configs
SET is_deleted = 1,
    enabled = 0,
    agent_enabled = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND config_code IN (
    'kling-v3-omni',
    'kling_image_to_video',
    'kling-v1-image-to-video',
    'kling-v2-master-image-to-video',
    'kling-image-generation-v1-model',
    'kling-image-generation-v3-model',
    'kling-v1-5-image-to-video',
    'kling-v1-6-image-to-video',
    'kling-v2-1-image-to-video',
    'kling-v2-1-master-image-to-video',
    'kling-v2-5-turbo-image-to-video',
    'kling-v2-6-motion-control',
    'kling-v2-6-image-to-video',
    'kling-v3-motion-control',
    'kling-v3-image-to-video',
    'kling-v3-text-to-video',
    'kling-video-o1-omni',
    '8'
  )
  AND config_code NOT IN (
    'kling-gateway-text-to-video',
    'kling-gateway-image-to-video',
    'kling-gateway-motion-control',
    'kling-gateway-multi-image-to-video',
    'kling-gateway-omni-video',
    'kling-gateway-image-generation',
    'kling-gateway-omni-image'
  );

UPDATE agent_model_configs
SET execution_task = CASE config_code
    WHEN 'kling-gateway-text-to-video' THEN 'text2video'
    WHEN 'kling-gateway-image-to-video' THEN 'image2video'
    WHEN 'kling-gateway-motion-control' THEN 'motion_control'
    WHEN 'kling-gateway-multi-image-to-video' THEN 'multi_image2video'
    WHEN 'kling-gateway-omni-video' THEN 'omni_video'
    WHEN 'kling-gateway-image-generation' THEN 'image_generation'
    WHEN 'kling-gateway-omni-image' THEN 'omni_image'
    ELSE execution_task
  END,
  enabled = 1,
  agent_enabled = 1,
  updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND config_code IN (
    'kling-gateway-text-to-video',
    'kling-gateway-image-to-video',
    'kling-gateway-motion-control',
    'kling-gateway-multi-image-to-video',
    'kling-gateway-omni-video',
    'kling-gateway-image-generation',
    'kling-gateway-omni-image'
  );

UPDATE ai_tools
SET is_deleted = 0,
    status = 'ONLINE',
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code IN (
    'kling-text-to-video',
    'kling-image-to-video',
    'kling-motion-control',
    'kling-multi-image-to-video',
    'kling-omni-video',
    'kling-image-generation',
    'kling-omni-image'
  );
