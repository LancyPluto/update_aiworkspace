SET NAMES utf8mb4;

ALTER TABLE workflow_step_attempts
  ADD COLUMN cancellation_generation BIGINT NOT NULL DEFAULT 0 AFTER attempt_no;
