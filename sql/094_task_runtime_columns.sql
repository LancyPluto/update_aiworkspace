SET NAMES utf8mb4;

-- SQL migrations run before the application DataInitializer. Keep the task
-- runtime schema complete even when the initializer is disabled or mocked.
DROP PROCEDURE IF EXISTS ensure_ai_tasks_runtime_columns;

DELIMITER $$
CREATE PROCEDURE ensure_ai_tasks_runtime_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'user_deleted'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN user_deleted TINYINT NOT NULL DEFAULT 0;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'user_deleted_at'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN user_deleted_at DATETIME NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'model_snapshot_json'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN model_snapshot_json TEXT NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'claimed_by'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN claimed_by VARCHAR(128) NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'claim_token'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN claim_token VARCHAR(128) NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'lease_until'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN lease_until DATETIME NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'claimed_at'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN claimed_at DATETIME NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'lease_renewed_at'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN lease_renewed_at DATETIME NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'execution_attempt'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN execution_attempt INT NOT NULL DEFAULT 0;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND index_name = 'idx_tasks_lease'
  ) THEN
    ALTER TABLE ai_tasks
      ADD KEY idx_tasks_lease(status, lease_until, id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND index_name = 'idx_tasks_claim_token'
  ) THEN
    ALTER TABLE ai_tasks
      ADD KEY idx_tasks_claim_token(claim_token);
  END IF;
END $$
DELIMITER ;

CALL ensure_ai_tasks_runtime_columns();
DROP PROCEDURE IF EXISTS ensure_ai_tasks_runtime_columns;
