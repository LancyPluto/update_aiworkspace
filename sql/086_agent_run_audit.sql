SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS agent_model_request_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  request_sequence INT NOT NULL,
  request_stage VARCHAR(64) NOT NULL,
  iteration_no INT NULL,
  model_provider_code VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  message_count INT NOT NULL DEFAULT 0,
  tool_count INT NOT NULL DEFAULT 0,
  estimated_input_tokens INT NOT NULL DEFAULT 0,
  skill_codes_json JSON NULL,
  payload_json JSON NULL,
  payload_sha256 CHAR(64) NOT NULL,
  payload_expires_at DATETIME NOT NULL,
  payload_expired_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_model_request_run_sequence (run_id, request_sequence),
  KEY idx_agent_model_request_run (run_id, id),
  KEY idx_agent_model_request_expiry (payload_expires_at, payload_expired_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_agent_audit_column_if_missing $$
CREATE PROCEDURE add_agent_audit_column_if_missing(
  IN p_table_name VARCHAR(64), IN p_column_name VARCHAR(64), IN p_column_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$
DELIMITER ;

CALL add_agent_audit_column_if_missing(
  'agent_context_snapshots', 'payload_sha256', '`payload_sha256` CHAR(64) NULL AFTER `snapshot_json`'
);
DROP PROCEDURE IF EXISTS add_agent_audit_column_if_missing;

CREATE TABLE IF NOT EXISTS agent_run_audit_reviews (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  expected_tool_code VARCHAR(128) NULL,
  final_category VARCHAR(64) NULL,
  review_note TEXT NULL,
  reviewed_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_run_audit_review_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
