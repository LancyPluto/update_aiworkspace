ALTER TABLE credit_logs
  ADD COLUMN agent_run_id BIGINT NULL AFTER task_id,
  ADD KEY idx_credit_logs_agent_run(agent_run_id);
