-- Add durable submission/reconciliation timing to PPT jobs.
ALTER TABLE ppt_jobs
    ADD COLUMN submission_started_at DATETIME NULL AFTER lease_expires_at,
    ADD COLUMN reconcile_started_at DATETIME NULL AFTER submission_started_at,
    ADD COLUMN last_engine_heartbeat_at DATETIME NULL AFTER reconcile_started_at,
    ADD COLUMN deadline_at DATETIME NULL AFTER last_engine_heartbeat_at;

UPDATE ppt_jobs
SET deadline_at = CASE job_type
    WHEN 'GENERATE_OUTLINE' THEN DATE_ADD(COALESCE(started_at, created_at), INTERVAL 10 MINUTE)
    WHEN 'GENERATE_DESCRIPTIONS' THEN DATE_ADD(COALESCE(started_at, created_at), INTERVAL 20 MINUTE)
    WHEN 'GENERATE_IMAGES' THEN DATE_ADD(COALESCE(started_at, created_at), INTERVAL 60 MINUTE)
    WHEN 'EXPORT_EDITABLE_PPTX' THEN DATE_ADD(COALESCE(started_at, created_at), INTERVAL 90 MINUTE)
    ELSE DATE_ADD(COALESCE(started_at, created_at), INTERVAL 15 MINUTE)
END
WHERE deadline_at IS NULL
  AND status IN ('CREATED', 'CREDIT_RESERVED', 'SUBMITTED', 'QUEUED', 'RUNNING', 'RECONCILING');

CREATE INDEX idx_ppt_job_deadline ON ppt_jobs(status, deadline_at);
