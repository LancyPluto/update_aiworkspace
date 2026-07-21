SET NAMES utf8mb4;

-- Completed workflow checkpoints can contain a full 60-shot model result. The
-- service keeps a much lower 1 MiB hard limit; MEDIUMTEXT prevents the database
-- column from becoming the accidental limit while retaining bounded storage.
DROP PROCEDURE IF EXISTS ensure_task_provider_checkpoint_capacity;

DELIMITER $$
CREATE PROCEDURE ensure_task_provider_checkpoint_capacity()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'provider_checkpoint_json'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'ai_tasks.provider_checkpoint_json is missing; apply migration 095 first';
  ELSEIF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'provider_checkpoint_json'
      AND data_type IN ('tinytext', 'text')
  ) THEN
    ALTER TABLE ai_tasks
      MODIFY COLUMN provider_checkpoint_json MEDIUMTEXT NULL;
  ELSEIF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tasks'
      AND column_name = 'provider_checkpoint_json'
      AND data_type IN ('mediumtext', 'longtext', 'json')
      AND is_nullable = 'YES'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'ai_tasks.provider_checkpoint_json has an unsupported type';
  END IF;
END $$
DELIMITER ;

CALL ensure_task_provider_checkpoint_capacity();
DROP PROCEDURE IF EXISTS ensure_task_provider_checkpoint_capacity;
