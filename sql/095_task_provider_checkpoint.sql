SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ensure_ai_tasks_provider_checkpoint;

DELIMITER $$
CREATE PROCEDURE ensure_ai_tasks_provider_checkpoint()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'provider_checkpoint_json'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN provider_checkpoint_json TEXT NULL;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'provider_checkpoint_version'
  ) THEN
    ALTER TABLE ai_tasks
      ADD COLUMN provider_checkpoint_version INT NOT NULL DEFAULT 0;
  END IF;
END $$
DELIMITER ;

CALL ensure_ai_tasks_provider_checkpoint();
DROP PROCEDURE IF EXISTS ensure_ai_tasks_provider_checkpoint;
