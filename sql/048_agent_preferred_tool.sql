-- Per-run user-selected tool preference for Agent intent routing.
ALTER TABLE agent_runs
  ADD COLUMN preferred_tool_code VARCHAR(64) NULL AFTER client_request_id;
