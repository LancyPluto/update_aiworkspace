SET NAMES utf8mb4;

ALTER TABLE workflow_step_charges
  MODIFY COLUMN attempt_id BIGINT NULL,
  ADD KEY idx_workflow_charge_billing_usage(billing_usage_id);
