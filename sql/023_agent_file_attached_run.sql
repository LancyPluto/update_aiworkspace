SET NAMES utf8mb4;

ALTER TABLE agent_files
  ADD COLUMN attached_run_id BIGINT NULL COMMENT 'Bound Agent run; NULL means pending attachment' AFTER status,
  ADD KEY idx_agent_files_attached_run (session_id, attached_run_id, id);
