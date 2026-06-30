CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  phone VARCHAR(32) UNIQUE,
  email VARCHAR(128) UNIQUE,
  nickname VARCHAR(64),
  avatar_url VARCHAR(512),
  bio VARCHAR(280),
  auto_publish_assets TINYINT NOT NULL DEFAULT 1,
  prompt_public_by_default TINYINT NOT NULL DEFAULT 0,
  user_type VARCHAR(32) NOT NULL DEFAULT 'USER',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE tool_categories (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category_code VARCHAR(64) NOT NULL UNIQUE,
  category_name VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE system_settings (
  setting_key VARCHAR(128) PRIMARY KEY,
  setting_value TEXT,
  setting_group VARCHAR(64) NOT NULL DEFAULT 'system',
  description VARCHAR(255),
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE system_setting_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  setting_key VARCHAR(128) NOT NULL,
  setting_value TEXT,
  operator_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_tools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_code VARCHAR(128) NOT NULL UNIQUE,
  tool_name VARCHAR(128) NOT NULL,
  category_id BIGINT,
  description TEXT,
  cover_url VARCHAR(512),
  tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  config_note TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  estimated_credit_cost INT NOT NULL DEFAULT 0,
  model_config_id BIGINT,
  template_id BIGINT,
  execution_handler VARCHAR(32),
  created_by BIGINT,
  updated_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE tool_templates (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_code VARCHAR(128) NOT NULL UNIQUE,
  template_name VARCHAR(128) NOT NULL,
  tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  execution_handler VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
  input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  config_note TEXT,
  default_system_prompt TEXT,
  default_user_prompt_template TEXT,
  default_output_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  handler_config_json TEXT,
  suggested_model_config_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  is_system TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tool_template_fields (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  field_type VARCHAR(32) NOT NULL,
  placeholder VARCHAR(255),
  options_json TEXT,
  validation_json TEXT,
  required TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tool_field_schemas (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  schema_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE tool_field_schema_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  schema_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  field_type VARCHAR(32) NOT NULL,
  placeholder VARCHAR(255),
  options_json JSON,
  validation_json JSON,
  required TINYINT NOT NULL DEFAULT 0,
  execution_required TINYINT NOT NULL DEFAULT 0,
  user_required TINYINT NOT NULL DEFAULT 0,
  default_value VARCHAR(512),
  agent_fill_strategy VARCHAR(32) NOT NULL DEFAULT 'default',
  risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE ai_tasks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NOT NULL,
  model_config_id BIGINT,
  field_schema_id BIGINT,
  prompt_version_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  progress TINYINT NOT NULL DEFAULT 0,
  progress_message TEXT,
  params_json JSON NOT NULL,
  model_snapshot_json TEXT,
  params_hash VARCHAR(128),
  idempotency_key VARCHAR(128),
  estimated_credit_cost INT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  max_retry_count INT NOT NULL DEFAULT 1,
  error_code VARCHAR(64),
  error_message TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  queued_at DATETIME,
  started_at DATETIME,
  finished_at DATETIME,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE ai_result_resources (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  content_text MEDIUMTEXT,
  content_json JSON,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE task_outbox_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_task_outbox_status_retry (status, next_retry_at, id),
  UNIQUE KEY uk_task_outbox_task_event (task_id, event_type)
);

CREATE TABLE credit_accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL UNIQUE,
  balance INT NOT NULL DEFAULT 0,
  frozen INT NOT NULL DEFAULT 0,
  total_granted INT NOT NULL DEFAULT 0,
  total_consumed INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE credit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  task_id BIGINT,
  agent_run_id BIGINT,
  log_type VARCHAR(32) NOT NULL,
  amount INT NOT NULL DEFAULT 0,
  frozen_amount INT NOT NULL DEFAULT 0,
  balance_before INT NOT NULL,
  balance_after INT NOT NULL,
  frozen_before INT NOT NULL,
  frozen_after INT NOT NULL,
  idempotency_key VARCHAR(128),
  operator_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  operator_id BIGINT,
  reason VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_credit_idem ON credit_logs(idempotency_key);

CREATE TABLE credit_recharge_packages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_code VARCHAR(64) NOT NULL UNIQUE,
  package_name VARCHAR(128) NOT NULL,
  credits INT NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
  validity_days INT NOT NULL DEFAULT 0,
  benefits_json TEXT,
  recommended TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE credit_recharge_orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  package_id BIGINT,
  credits INT NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
  payment_channel VARCHAR(32) NOT NULL DEFAULT 'MOCK',
  status VARCHAR(32) NOT NULL DEFAULT 'WAITING_PAYMENT',
  status_reason VARCHAR(255),
  pay_url TEXT,
  qr_code_url VARCHAR(512),
  external_trade_no VARCHAR(128),
  idempotency_key VARCHAR(128),
  order_type VARCHAR(32) NOT NULL DEFAULT 'CREDITS',
  gift_card_package_id BIGINT,
  paid_at DATETIME,
  credited_at DATETIME,
  closed_at DATETIME,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_recharge_user_idem ON credit_recharge_orders(user_id, idempotency_key);
CREATE UNIQUE INDEX uk_recharge_external_trade_no ON credit_recharge_orders(external_trade_no);

CREATE TABLE tool_prompts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  prompt_code VARCHAR(128) NOT NULL,
  prompt_name VARCHAR(128) NOT NULL,
  active_version_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE tool_prompt_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  prompt_id BIGINT NOT NULL,
  version_no VARCHAR(32) NOT NULL,
  system_prompt TEXT,
  user_prompt_template MEDIUMTEXT NOT NULL,
  output_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME
);

CREATE TABLE ai_task_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  from_status VARCHAR(32),
  to_status VARCHAR(32),
  event_type VARCHAR(64) NOT NULL,
  message TEXT,
  error_code VARCHAR(64),
  error_message TEXT,
  operator_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  operator_id BIGINT,
  metadata_json JSON,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  workspace_id BIGINT,
  active_leaf_message_id BIGINT,
  title VARCHAR(120) NOT NULL,
  conversation_summary CLOB,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_workspaces (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  workspace_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(owner_user_id, workspace_type)
);

CREATE TABLE agent_workspace_members (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(workspace_id, user_id)
);

CREATE TABLE agent_workspace_memory_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  memory_type VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL,
  content CLOB NOT NULL,
  source_run_id BIGINT,
  source_message_id BIGINT,
  source_tool_call_id BIGINT,
  importance INT NOT NULL DEFAULT 5,
  confidence DOUBLE NOT NULL DEFAULT 0.7,
  pinned BOOLEAN NOT NULL DEFAULT FALSE,
  tags_json JSON,
  metadata_json JSON,
  last_accessed_at DATETIME,
  access_count INT NOT NULL DEFAULT 0,
  expires_at DATETIME,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content_text CLOB NOT NULL,
  content_json JSON,
  run_id BIGINT,
  parent_message_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  superseded_at DATETIME,
  edited_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  intent VARCHAR(64),
  model_config_id BIGINT,
  model_provider_code VARCHAR(64),
  model_name VARCHAR(128),
  estimated_credits INT NOT NULL DEFAULT 0,
  consumed_credits INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  error_message CLOB,
  started_at DATETIME,
  finished_at DATETIME,
  parent_run_id BIGINT,
  source_user_message_id BIGINT,
  context_snapshot_id BIGINT,
  client_request_id VARCHAR(64),
  preferred_tool_code VARCHAR(64),
  graph_checkpoint_json CLOB,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_agent_runs_user_client ON agent_runs (user_id, client_request_id);
CREATE INDEX idx_agent_runs_session_user_id ON agent_runs (session_id, user_id, id);
CREATE INDEX idx_agent_runs_model_config ON agent_runs (model_config_id);
CREATE INDEX idx_agent_runs_context_snapshot ON agent_runs (context_snapshot_id);
CREATE INDEX idx_agent_messages_session_active ON agent_messages (session_id, status, id);

CREATE TABLE agent_context_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  workspace_id BIGINT,
  model_config_id BIGINT,
  model_provider_code VARCHAR(64),
  model_name VARCHAR(128),
  strategy VARCHAR(64) NOT NULL,
  max_history_messages INT NOT NULL DEFAULT 20,
  history_message_count INT NOT NULL DEFAULT 0,
  file_count INT NOT NULL DEFAULT 0,
  file_chunk_count INT NOT NULL DEFAULT 0,
  memory_item_count INT NOT NULL DEFAULT 0,
  estimated_input_tokens INT NOT NULL DEFAULT 0,
  snapshot_json JSON,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_context_snapshots_run ON agent_context_snapshots (run_id, id);
CREATE INDEX idx_agent_context_snapshots_session ON agent_context_snapshots (session_id, id);
CREATE INDEX idx_agent_context_snapshots_user ON agent_context_snapshots (user_id, id);

CREATE TABLE agent_run_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_text CLOB,
  event_json JSON,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_tool_calls (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  task_id BIGINT,
  status VARCHAR(32) NOT NULL,
  arguments_json JSON NOT NULL,
  result_json JSON,
  error_code VARCHAR(64),
  error_message CLOB,
  started_at DATETIME,
  finished_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE community_posts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL UNIQUE,
  modality VARCHAR(32) NOT NULL,
  cover_url VARCHAR(1024),
  media_url VARCHAR(1024),
  title VARCHAR(160) NOT NULL,
  description VARCHAR(500),
  prompt_visible TINYINT NOT NULL DEFAULT 0,
  prompt_snapshot CLOB,
  tool_code VARCHAR(128),
  tool_name VARCHAR(128),
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
  featured TINYINT NOT NULL DEFAULT 0,
  pinned TINYINT NOT NULL DEFAULT 0,
  topic VARCHAR(64),
  same_style_count BIGINT NOT NULL DEFAULT 0,
  audit_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
  audit_reason VARCHAR(255),
  view_count BIGINT NOT NULL DEFAULT 0,
  detail_click_count BIGINT NOT NULL DEFAULT 0,
  share_count BIGINT NOT NULL DEFAULT 0,
  quality_score BIGINT NOT NULL DEFAULT 0,
  last_featured_at DATETIME,
  like_count BIGINT NOT NULL DEFAULT 0,
  favorite_count BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_community_posts_user_status_id ON community_posts(user_id, status, id);
CREATE INDEX idx_community_posts_status_id ON community_posts(status, id);
CREATE INDEX idx_community_posts_quality ON community_posts(status, audit_status, pinned, featured, quality_score, id);
CREATE INDEX idx_community_posts_discovery ON community_posts(status, pinned, featured, id);
CREATE INDEX idx_community_posts_modality_id ON community_posts(status, modality, id);
CREATE INDEX idx_community_posts_topic_id ON community_posts(status, topic, id);
CREATE INDEX idx_community_posts_popular ON community_posts(status, like_count, favorite_count, id);
CREATE INDEX idx_community_posts_same_style ON community_posts(status, same_style_count, id);

CREATE TABLE community_post_likes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(post_id, user_id)
);

CREATE TABLE community_post_favorites (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(post_id, user_id)
);

CREATE TABLE community_post_tags (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  tag VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(post_id, tag)
);
CREATE INDEX idx_community_post_tags_tag_post ON community_post_tags(tag, post_id);

CREATE TABLE community_post_reports (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  reporter_user_id BIGINT NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  admin_note VARCHAR(500) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_community_post_reports_user ON community_post_reports(post_id, reporter_user_id);
CREATE INDEX idx_community_post_reports_status_created ON community_post_reports(status, created_at, id);
CREATE INDEX idx_community_post_reports_post ON community_post_reports(post_id, status, id);

CREATE TABLE community_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT,
  user_id BIGINT,
  event_type VARCHAR(64) NOT NULL,
  source VARCHAR(64),
  tool_code VARCHAR(128),
  task_id BIGINT,
  credits INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_community_events_type_created ON community_events(event_type, created_at, id);
CREATE INDEX idx_community_events_post_type ON community_events(post_id, event_type, id);
CREATE INDEX idx_community_events_tool ON community_events(tool_code, event_type, id);

CREATE TABLE community_collections (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(80) NOT NULL,
  default_collection TINYINT NOT NULL DEFAULT 0,
  item_count BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_community_collections_user_name ON community_collections(user_id, name);

CREATE TABLE community_collection_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  collection_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(collection_id, post_id)
);
CREATE INDEX idx_community_collection_items_collection ON community_collection_items(collection_id, id);
CREATE INDEX idx_community_collection_items_user ON community_collection_items(user_id, collection_id, id);
CREATE INDEX idx_agent_tool_calls_task_id ON agent_tool_calls(task_id);
CREATE INDEX idx_agent_tool_calls_context_recent ON agent_tool_calls(user_id, status, id);

CREATE TABLE agent_tool_preferences (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  auto_call_enabled TINYINT NOT NULL DEFAULT 0,
  disabled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, tool_code)
);

CREATE TABLE agent_pending_tool_context (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  selected_tool_code VARCHAR(64),
  candidate_tool_codes_json CLOB,
  collected_arguments_json CLOB,
  missing_arguments_json CLOB,
  clarifying_question VARCHAR(2000),
  confirmation_required TINYINT DEFAULT 0,
  source VARCHAR(32) DEFAULT 'intent_router',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_tool_descriptor_extension (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  tool_code VARCHAR(64) NOT NULL UNIQUE,
  agent_enabled TINYINT NOT NULL DEFAULT 1,
  agent_recommendable TINYINT NOT NULL DEFAULT 1,
  agent_auto_callable TINYINT NOT NULL DEFAULT 0,
  confirmation_policy VARCHAR(32) DEFAULT 'auto',
  risk_level VARCHAR(16) DEFAULT 'low',
  keywords_json CLOB,
  example_prompts_json CLOB,
  applicable_scenarios_json CLOB,
  not_applicable_scenarios_json CLOB,
  result_schema_json CLOB,
  output_type VARCHAR(32) DEFAULT 'text',
  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  health_message VARCHAR(512),
  health_checked_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(128),
  file_size BIGINT NOT NULL DEFAULT 0,
  storage_path VARCHAR(1024) NOT NULL,
  status VARCHAR(32) NOT NULL,
  attached_run_id BIGINT,
  extracted_text CLOB,
  error_message CLOB,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_file_chunks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content_text CLOB NOT NULL,
  metadata_json CLOB,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE model_vendors (
  vendor_code VARCHAR(64) PRIMARY KEY,
  vendor_label VARCHAR(128) NOT NULL,
  icon_asset VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE model_vendor_accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  vendor_code VARCHAR(64) NOT NULL,
  account_name VARCHAR(128) NOT NULL DEFAULT '默认账户',
  base_url VARCHAR(512),
  api_key VARCHAR(1024),
  extra_auth_json TEXT,
  console_url VARCHAR(512),
  balance_url VARCHAR(512),
  console_cookie TEXT,
  console_cookie_status VARCHAR(20) DEFAULT 'UNKNOWN',
  balance_query_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  balance_amount DECIMAL(18,4),
  balance_currency VARCHAR(8) DEFAULT 'CNY',
  balance_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  balance_low_threshold DECIMAL(18,4),
  balance_updated_at DATETIME,
  balance_error_message VARCHAR(512),
  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_model_configs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  vendor_account_id BIGINT,
  display_name VARCHAR(128),
  config_code VARCHAR(64),
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  base_url VARCHAR(512),
  api_key VARCHAR(512),
  extra_auth_json TEXT,
  minimax_group_id VARCHAR(128),
  console_url VARCHAR(512),
  balance_url VARCHAR(512),
  docs_url VARCHAR(512),
  timeout_seconds INT NOT NULL DEFAULT 60,
  input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
  unit_price DECIMAL(18,8) NOT NULL DEFAULT 0,
  capabilities TEXT,
  enabled TINYINT NOT NULL DEFAULT 1,
    agent_enabled TINYINT NOT NULL DEFAULT 1,
    is_default TINYINT NOT NULL DEFAULT 0,
    last_test_success TINYINT,
    last_test_message VARCHAR(500),
    last_test_at DATETIME,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
  );

CREATE TABLE model_provider_metadata (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_code VARCHAR(64) NOT NULL UNIQUE,
  label VARCHAR(128) NOT NULL,
  capabilities_json TEXT NOT NULL,
  default_base_url VARCHAR(512),
  default_model VARCHAR(128),
  billing_default VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
  provider_protocol VARCHAR(64),
  vendor_kind VARCHAR(64),
  upstream_vendor VARCHAR(64),
  test_strategy VARCHAR(32) NOT NULL DEFAULT 'accept_only',
  worker_ready TINYINT NOT NULL DEFAULT 0,
  adapter_installed TINYINT NOT NULL DEFAULT 0,
  adapter_key VARCHAR(64),
  metadata_version VARCHAR(64) NOT NULL DEFAULT 'db',
  auth_schema_json TEXT,
  model_param_schema_json TEXT,
  description TEXT,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE billing_usage_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_type VARCHAR(32) NOT NULL,
  source_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  model_config_id BIGINT,
  provider VARCHAR(64),
  model_name VARCHAR(128),
  prompt_tokens INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  total_tokens INT NOT NULL DEFAULT 0,
  input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
  input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
  billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
  billable_units INT NOT NULL DEFAULT 0,
  unit_price DECIMAL(18,8) NOT NULL DEFAULT 0,
  cost_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  vendor_cost_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  charged_credits INT NOT NULL DEFAULT 0,
  customer_charge_credits INT NOT NULL DEFAULT 0,
  margin_credits INT NOT NULL DEFAULT 0,
  markup_ratio DECIMAL(10,4) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE vendor_balance_adjustments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  billing_usage_log_id BIGINT NOT NULL,
  vendor_account_id BIGINT NOT NULL,
  balance_before DECIMAL(18,6) NOT NULL,
  balance_after DECIMAL(18,6) NOT NULL,
  deducted_amount DECIMAL(18,6) NOT NULL,
  balance_currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (billing_usage_log_id)
);

CREATE TABLE pricing_margins (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
  scope_ref BIGINT NOT NULL DEFAULT 0,
  markup_ratio DECIMAL(10,4) NOT NULL DEFAULT 1.2000,
  min_credits INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_pricing_margin_scope UNIQUE (scope_type, scope_ref)
);

CREATE TABLE pricing_rules (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type VARCHAR(16) NOT NULL DEFAULT 'MODEL',
  scope_ref BIGINT NOT NULL DEFAULT 0,
  param_key VARCHAR(64) NOT NULL,
  rule_type VARCHAR(16) NOT NULL DEFAULT 'MULTIPLIER',
  match_op VARCHAR(8) NOT NULL DEFAULT 'EQ',
  match_value VARCHAR(64),
  factor DECIMAL(10,4) NOT NULL DEFAULT 1.0000,
  extra_credits INT NOT NULL DEFAULT 0,
  priority INT NOT NULL DEFAULT 100,
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO pricing_margins (scope_type, scope_ref, markup_ratio, min_credits, enabled, remark)
VALUES ('GLOBAL', 0, 1.2000, 0, 1, '默认全局加价 20%');

-- Default model config for TEXT_GENERATION tools (tests create tools without model_config_id)
INSERT INTO agent_model_configs (
  display_name,
  config_code,
  provider,
  model_name,
  base_url,
  timeout_seconds,
  billing_unit,
  unit_price,
  capabilities,
  enabled,
  agent_enabled,
  is_default,
  is_deleted
) VALUES (
  'Test text generation',
  'default_text_generation',
  'minimax',
  'MiniMax-M2.7',
  'https://api.minimaxi.com/v1',
  120,
  'TOKEN_PER_M',
  0,
  '["TEXT_GENERATION"]',
  1,
  1,
  1,
  0
);

CREATE TABLE ai_market_tools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL,
  icon_url VARCHAR(512) NOT NULL,
  description VARCHAR(256),
  enabled TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  primary_color VARCHAR(16),
  welcome_message CLOB,
  capabilities_json CLOB NOT NULL,
  model_config_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE ai_market_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  tool_id VARCHAR(64) NOT NULL,
  title VARCHAR(128) NOT NULL DEFAULT '新对话',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE ai_market_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id VARCHAR(64) NOT NULL UNIQUE,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  content CLOB NOT NULL,
  params_json CLOB,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_market_message_attachments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id VARCHAR(64) NOT NULL,
  file_id VARCHAR(64) NOT NULL,
  file_name VARCHAR(256) NOT NULL,
  file_size BIGINT NOT NULL,
  content_type VARCHAR(128) NOT NULL
);

CREATE TABLE ai_market_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  tool_id VARCHAR(64),
  original_name VARCHAR(256) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  file_size BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_upload_assets (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  file_id VARCHAR(64) NOT NULL,
  asset_kind VARCHAR(16) NOT NULL DEFAULT 'file',
  original_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(128),
  file_size BIGINT,
  url VARCHAR(1024) NOT NULL,
  storage_path VARCHAR(1024),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ai_market_tools (tool_id, name, icon_url, description, enabled, sort_order, primary_color, welcome_message, capabilities_json)
VALUES (
  'doubao',
  '豆包',
  '/generated/icons/doubao.png',
  '生图 + 文件阅读',
  1,
  10,
  '#f97316',
  '你好，我是豆包~',
  '[{"type":"imageGeneration","config":{"aspectRatios":["1:1","16:9"],"defaultRatio":"1:1"}},{"type":"fileReading","config":{"supportedFileTypes":["pdf","txt","png"],"maxSizeMB":20}}]'
);

CREATE TABLE ppt_project_bindings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NOT NULL,
  banana_project_id VARCHAR(64) NOT NULL,
  creation_type VARCHAR(32) NOT NULL,
  title VARCHAR(255),
  status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ppt_step_billing_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  step_code VARCHAR(64) NOT NULL,
  credits_charged INT NOT NULL,
  credit_log_id BIGINT,
  client_request_id VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tool_workflows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  workflow_name VARCHAR(128) NOT NULL DEFAULT 'default',
  nodes_json CLOB NOT NULL,
  edges_json CLOB NOT NULL,
  groups_json CLOB,
  config_json CLOB,
  version INT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT,
  updated_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_tool_workflow UNIQUE (tool_id, workflow_name)
);

CREATE TABLE tool_workflow_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workflow_id BIGINT NOT NULL,
  version INT NOT NULL,
  nodes_json CLOB NOT NULL,
  edges_json CLOB NOT NULL,
  groups_json CLOB,
  config_json CLOB,
  snapshot_label VARCHAR(255),
  created_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_workflow_version UNIQUE (workflow_id, version)
);

CREATE TABLE IF NOT EXISTS user_generation_subjects (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  subject_code VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  provider_code VARCHAR(64) NOT NULL,
  vendor_account_ref VARCHAR(128) NOT NULL,
  reference_type VARCHAR(32) NOT NULL,
  preview_url VARCHAR(1024) NULL,
  reference_json CLOB NOT NULL,
  upstream_element_id VARCHAR(128) NULL,
  sync_task_id VARCHAR(128) NULL,
  sync_status VARCHAR(32) NOT NULL,
  sync_error VARCHAR(512) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO model_vendor_accounts (
  vendor_code, account_name, base_url, api_key, extra_auth_json, enabled, is_deleted
) VALUES (
  'kling',
  'test-default',
  'https://api-beijing.klingai.com',
  '',
  '{"accessKey":"test-access","secretKey":"test-secret"}',
  1,
  0
);

INSERT INTO credit_recharge_packages (
  package_code,
  package_name,
  credits,
  price_amount,
  currency,
  validity_days,
  benefits_json,
  recommended,
  sort_order,
  status
) VALUES (
  'ci_recharge_1000',
  '测试套餐',
  1000,
  10.00,
  'CNY',
  30,
  '["优先排队"]',
  1,
  10,
  'ACTIVE'
);

CREATE TABLE IF NOT EXISTS gift_card_packages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_code VARCHAR(64) NOT NULL UNIQUE,
  package_name VARCHAR(128) NOT NULL,
  credits INT NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL,
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  card_theme VARCHAR(32) NOT NULL DEFAULT 'classic',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS gift_cards (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  card_code VARCHAR(64) NOT NULL UNIQUE,
  package_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  original_user_id BIGINT NOT NULL,
  credits INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'UNUSED',
  recharge_order_id BIGINT,
  redeemed_at DATETIME NULL,
  gifted_from_user_id BIGINT NULL,
  gifted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO gift_card_packages (package_code, package_name, credits, price_amount, card_theme, sort_order) VALUES
('gift_200', '200算力礼品卡', 200, 19.00, 'blue', 1),
('gift_500', '500算力礼品卡', 500, 45.00, 'purple', 2),
('gift_1000', '1000算力礼品卡', 1000, 88.00, 'gold', 3),
('gift_3000', '3000算力礼品卡', 3000, 249.00, 'dark', 4);
