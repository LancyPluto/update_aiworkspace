ALTER TABLE model_channel_nodes
  ADD COLUMN timeout_seconds INT NOT NULL DEFAULT 90 AFTER model_name;

ALTER TABLE model_call_logs
  ADD COLUMN error_category VARCHAR(64) AFTER error_code,
  ADD COLUMN attempt_no INT AFTER estimated_cost_cents,
  ADD COLUMN fallback_from_node_id BIGINT AFTER attempt_no,
  ADD COLUMN response_metadata_json TEXT AFTER fallback_from_node_id;
