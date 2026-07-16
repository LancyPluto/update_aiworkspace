SET NAMES utf8mb4;

ALTER TABLE billing_usage_logs
  ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER id,
  ADD UNIQUE KEY uk_billing_usage_idempotency(idempotency_key),
  ADD KEY idx_billing_usage_user_source_created(user_id, source_type, created_at);
