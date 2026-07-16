SET NAMES utf8mb4;

-- Follow-up only: 088 is immutable once recorded by the deployment pipeline.
ALTER TABLE credit_logs
  ADD COLUMN source_type VARCHAR(32) NULL AFTER agent_run_id,
  ADD COLUMN source_ref BIGINT NULL AFTER source_type;
