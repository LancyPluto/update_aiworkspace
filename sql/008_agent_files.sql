SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS agent_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NULL,
  file_size BIGINT NOT NULL DEFAULT 0,
  storage_path VARCHAR(1024) NOT NULL,
  status VARCHAR(32) NOT NULL,
  extracted_text MEDIUMTEXT NULL,
  error_message VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_agent_files_session_id (session_id, id),
  KEY idx_agent_files_user_id (user_id, id),
  KEY idx_agent_files_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

