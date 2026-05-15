CREATE TABLE IF NOT EXISTS subscription_plans (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_code VARCHAR(64) NOT NULL UNIQUE,
  plan_name VARCHAR(128) NOT NULL,
  plan_type VARCHAR(32) NOT NULL,
  duration_days INT NOT NULL DEFAULT 30,
  price_cents INT NOT NULL DEFAULT 0,
  credit_amount INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  description VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_subscription_plans_type_status(plan_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS plan_entitlements (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  pool_id BIGINT NULL,
  entitlement_type VARCHAR(32) NOT NULL,
  hourly_limit INT NULL,
  daily_limit INT NULL,
  max_concurrency INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_plan_entitlements_plan(plan_id),
  KEY idx_plan_entitlements_pool(pool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_subscriptions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  plan_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  starts_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  source_order_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_subscriptions_source_order(source_order_id),
  KEY idx_user_subscriptions_user_status(user_id, status, expires_at),
  KEY idx_user_subscriptions_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  product_type VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  channel VARCHAR(32) NOT NULL,
  amount_cents INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  provider_trade_no VARCHAR(128),
  idempotency_key VARCHAR(128),
  paid_at DATETIME NULL,
  closed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_payment_orders_user(user_id, created_at),
  KEY idx_payment_orders_status(status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_transactions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  provider VARCHAR(32) NOT NULL,
  provider_trade_no VARCHAR(128),
  event_type VARCHAR(64) NOT NULL,
  raw_payload_json TEXT,
  verified TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_payment_transactions_provider_event(provider, provider_trade_no, event_type),
  KEY idx_payment_transactions_order(order_id),
  KEY idx_payment_transactions_provider(provider, provider_trade_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_fulfillments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  product_type VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'FULFILLED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_payment_fulfillments_order(order_id),
  KEY idx_payment_fulfillments_user(user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_channel_pools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  pool_code VARCHAR(64) NOT NULL UNIQUE,
  pool_name VARCHAR(128) NOT NULL,
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  spec_label VARCHAR(64) NOT NULL,
  description VARCHAR(512),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_model_channel_pools_status(status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_channel_nodes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  pool_id BIGINT NOT NULL,
  node_code VARCHAR(64) NOT NULL UNIQUE,
  display_label VARCHAR(128),
  provider_protocol VARCHAR(64) NOT NULL DEFAULT 'openai_compatible',
  base_url VARCHAR(512),
  api_key VARCHAR(1024),
  model_name VARCHAR(128),
  timeout_seconds INT NOT NULL DEFAULT 90,
  max_concurrency INT NOT NULL DEFAULT 1,
  current_concurrency INT NOT NULL DEFAULT 0,
  hourly_limit INT NULL,
  daily_limit INT NULL,
  today_used INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  last_error VARCHAR(512),
  note VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_model_channel_nodes_pool(pool_id, status),
  KEY idx_model_channel_nodes_health(health_status, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_chat_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL DEFAULT 'New chat',
  pool_id BIGINT NULL,
  node_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_model_chat_sessions_user(user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_chat_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content_text MEDIUMTEXT NOT NULL,
  pool_id BIGINT NULL,
  node_id BIGINT NULL,
  token_count INT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_model_chat_messages_session(session_id, id),
  KEY idx_model_chat_messages_user(user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_call_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(128),
  user_id BIGINT,
  task_id BIGINT,
  agent_run_id BIGINT,
  chat_session_id BIGINT,
  pool_id BIGINT,
  node_id BIGINT,
  model_config_id BIGINT,
  provider VARCHAR(64),
  model_name VARCHAR(128),
  request_type VARCHAR(32) NOT NULL DEFAULT 'CHAT',
  streaming TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  error_code VARCHAR(64),
  error_category VARCHAR(64),
  error_message TEXT,
  latency_ms INT,
  prompt_tokens INT,
  completion_tokens INT,
  total_tokens INT,
  estimated_cost_cents INT,
  attempt_no INT,
  fallback_from_node_id BIGINT,
  response_metadata_json TEXT,
  started_at DATETIME,
  finished_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_model_call_logs_user(user_id, created_at),
  KEY idx_model_call_logs_node(node_id, created_at),
  KEY idx_model_call_logs_status(status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_access_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  subscription_id BIGINT NULL,
  pool_id BIGINT NOT NULL,
  node_id BIGINT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_message VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_model_access_logs_user(user_id, created_at),
  KEY idx_model_access_logs_pool(pool_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO subscription_plans (plan_code, plan_name, plan_type, duration_days, price_cents, credit_amount, description)
VALUES
  ('compute_starter_100', 'Starter Compute 100', 'COMPUTE_CREDIT', 0, 990, 100, 'Starter compute credits for platform workflows'),
  ('global_model_monthly', 'Global Model Monthly', 'MODEL_CHANNEL', 30, 16000, 0, 'Monthly access to selected global model pools')
ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name), plan_type = VALUES(plan_type), status = 'ACTIVE';

INSERT INTO model_channel_pools (pool_code, pool_name, provider, model_name, spec_label, description, sort_order)
VALUES
  ('gemini_pro_pool', 'Gemini Pro Pool', 'gemini', 'gemini-pro', 'PRO', 'Shared Gemini Pro-compatible API channel pool', 10),
  ('gpt_pool', 'GPT Pool', 'openai', 'gpt-4.1', 'PRO', 'Shared GPT-compatible API channel pool', 20),
  ('claude_pool', 'Claude Pool', 'anthropic', 'claude-sonnet', 'PRO', 'Shared Claude-compatible API channel pool', 30)
ON DUPLICATE KEY UPDATE pool_name = VALUES(pool_name), provider = VALUES(provider), model_name = VALUES(model_name);
