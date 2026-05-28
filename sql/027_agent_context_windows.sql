SET NAMES utf8mb4;

ALTER TABLE agent_runs
  ADD COLUMN model_config_id BIGINT NULL AFTER intent,
  ADD COLUMN context_snapshot_id BIGINT NULL AFTER source_user_message_id,
  ADD KEY idx_agent_runs_model_config (model_config_id),
  ADD KEY idx_agent_runs_context_snapshot (context_snapshot_id);

CREATE TABLE IF NOT EXISTS agent_context_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  workspace_id BIGINT NULL,
  model_config_id BIGINT NULL,
  model_provider_code VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  strategy VARCHAR(64) NOT NULL,
  max_history_messages INT NOT NULL DEFAULT 20,
  history_message_count INT NOT NULL DEFAULT 0,
  file_count INT NOT NULL DEFAULT 0,
  file_chunk_count INT NOT NULL DEFAULT 0,
  memory_item_count INT NOT NULL DEFAULT 0,
  estimated_input_tokens INT NOT NULL DEFAULT 0,
  snapshot_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_context_snapshots_run (run_id, id),
  KEY idx_agent_context_snapshots_session (session_id, id),
  KEY idx_agent_context_snapshots_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
