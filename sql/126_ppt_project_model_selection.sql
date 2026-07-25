-- PPT project-level platform model selection.
-- Nullable means "platform auto"; explicit IDs are validated against the shared
-- executable model catalog before they are persisted.

ALTER TABLE ppt_projects
  ADD COLUMN text_model_config_id BIGINT NULL AFTER engine_strategy,
  ADD COLUMN image_model_config_id BIGINT NULL AFTER text_model_config_id,
  ADD KEY idx_ppt_project_text_model (text_model_config_id),
  ADD KEY idx_ppt_project_image_model (image_model_config_id);
