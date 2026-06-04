ALTER TABLE agent_model_configs
  ADD COLUMN last_test_success TINYINT(1) NULL COMMENT '1=pass,0=fail' AFTER is_default,
  ADD COLUMN last_test_message VARCHAR(512) NULL AFTER last_test_success,
  ADD COLUMN last_test_at DATETIME NULL AFTER last_test_message;
