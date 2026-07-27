SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS ensure_workflow_step_display_snapshot;

DELIMITER $$
CREATE PROCEDURE ensure_workflow_step_display_snapshot()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_run_steps'
      AND column_name = 'node_title'
  ) THEN
    ALTER TABLE workflow_run_steps
      ADD COLUMN node_title TEXT NULL AFTER node_id;
  ELSEIF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_run_steps'
      AND column_name = 'node_title'
      AND (
        data_type IN ('varchar', 'tinytext')
        OR (data_type = 'text' AND is_nullable = 'NO')
      )
  ) THEN
    ALTER TABLE workflow_run_steps
      MODIFY COLUMN node_title TEXT NULL AFTER node_id;
  ELSEIF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_run_steps'
      AND column_name = 'node_title'
      AND data_type IN ('text', 'mediumtext', 'longtext')
      AND is_nullable = 'YES'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'workflow_run_steps.node_title has an incompatible definition';
  END IF;
END $$
DELIMITER ;

CALL ensure_workflow_step_display_snapshot();
DROP PROCEDURE IF EXISTS ensure_workflow_step_display_snapshot;
