-- Mark which system model configs can be selected by the user-facing Agent.
ALTER TABLE agent_model_configs
  ADD COLUMN agent_enabled TINYINT NOT NULL DEFAULT 1 AFTER enabled,
  ADD KEY idx_agent_model_configs_agent_enabled (agent_enabled, enabled, is_deleted, is_default, id);
