-- PPT task-scoped platform model gateway.

CREATE TABLE IF NOT EXISTS ppt_model_invocations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  ppt_job_id BIGINT NOT NULL,
  ai_task_id BIGINT NOT NULL,
  capability VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(191) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ppt_model_invocation_job_key (ppt_job_id, idempotency_key),
  UNIQUE KEY uk_ppt_model_invocation_task (ai_task_id),
  KEY idx_ppt_model_invocation_project (project_id, id),
  KEY idx_ppt_model_invocation_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ai_tools (
  tool_code, tool_name, category_id, description, cover_url,
  tool_type, input_modality, output_modality, config_note,
  status, estimated_credit_cost, execution_handler,
  required_model_capabilities, execution_mode, billing_mode,
  agent_surface_enabled, is_deleted
)
SELECT
  'ppt_platform_text_invocation',
  'PPT 平台文本调用（内部）',
  category.id,
  'PPT 引擎通过平台模型路由执行文本生成；不直接向用户展示。',
  NULL,
  'TEXT_GENERATION',
  'TEXT',
  'TEXT',
  '<!-- ai-tool-runtime:{"toolKind":"ppt_platform_text","systemPrompt":"","adminPrompt":"","userInputs":[]} -->',
  'ONLINE',
  0,
  'TEXT_GENERATION',
  '["TEXT_GENERATION"]',
  'INTERNAL',
  'PARENT_PPT_JOB',
  0,
  0
FROM tool_categories category
ORDER BY category.id
LIMIT 1
ON DUPLICATE KEY UPDATE
  status = 'ONLINE',
  execution_mode = 'INTERNAL',
  billing_mode = 'PARENT_PPT_JOB',
  agent_surface_enabled = 0,
  required_model_capabilities = '["TEXT_GENERATION"]',
  updated_at = CURRENT_TIMESTAMP;

INSERT INTO ai_tools (
  tool_code, tool_name, category_id, description, cover_url,
  tool_type, input_modality, output_modality, config_note,
  status, estimated_credit_cost, execution_handler,
  required_model_capabilities, execution_mode, billing_mode,
  agent_surface_enabled, is_deleted
)
SELECT
  'ppt_platform_image_invocation',
  'PPT 平台生图调用（内部）',
  category.id,
  'PPT 引擎通过平台模型路由执行图片生成；不直接向用户展示。',
  NULL,
  'IMAGE_GENERATION',
  'TEXT',
  'IMAGE',
  '<!-- ai-tool-runtime:{"toolKind":"ppt_platform_image","systemPrompt":"","adminPrompt":"","userInputs":[]} -->',
  'ONLINE',
  0,
  'IMAGE_GENERATION',
  '["IMAGE_GENERATION"]',
  'INTERNAL',
  'PARENT_PPT_JOB',
  0,
  0
FROM tool_categories category
ORDER BY category.id
LIMIT 1
ON DUPLICATE KEY UPDATE
  status = 'ONLINE',
  execution_mode = 'INTERNAL',
  billing_mode = 'PARENT_PPT_JOB',
  agent_surface_enabled = 0,
  required_model_capabilities = '["IMAGE_GENERATION"]',
  updated_at = CURRENT_TIMESTAMP;
