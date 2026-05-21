SET NAMES utf8mb4;

ALTER TABLE agent_files
  ADD COLUMN attached_run_id BIGINT NULL COMMENT '绑定到的 Agent run；NULL 表示待发送附件' AFTER status,
  ADD KEY idx_agent_files_attached_run (session_id, attached_run_id, id);
