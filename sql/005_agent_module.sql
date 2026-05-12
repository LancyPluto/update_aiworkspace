CREATE TABLE IF NOT EXISTS agent_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  workspace_id BIGINT NULL,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_agent_sessions_user_updated (user_id, updated_at),
  KEY idx_agent_sessions_workspace (workspace_id),
  KEY idx_agent_sessions_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content_text MEDIUMTEXT NOT NULL,
  content_json JSON NULL,
  run_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_messages_session_id (session_id, id),
  KEY idx_agent_messages_user_id (user_id, id),
  KEY idx_agent_messages_run_id (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  intent VARCHAR(64) NULL,
  model_provider_code VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  estimated_credits INT NOT NULL DEFAULT 0,
  consumed_credits INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_agent_runs_user_created (user_id, created_at),
  KEY idx_agent_runs_session_created (session_id, created_at),
  KEY idx_agent_runs_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_run_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_text TEXT NULL,
  event_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_run_events_run_id (run_id, id),
  KEY idx_agent_run_events_user_id (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_tool_calls (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  arguments_json JSON NOT NULL,
  result_json JSON NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_tool_calls_run_id (run_id),
  KEY idx_agent_tool_calls_user_id (user_id, id),
  KEY idx_agent_tool_calls_tool_code (tool_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
