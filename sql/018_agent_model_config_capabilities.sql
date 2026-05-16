-- agent_model_configs: per-config capability tags (JSON array of execution handlers)
ALTER TABLE agent_model_configs
  ADD COLUMN capabilities TEXT NULL COMMENT 'JSON array e.g. ["TEXT_GENERATION","IMAGE_GENERATION"]';
