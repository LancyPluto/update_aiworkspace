CREATE TABLE IF NOT EXISTS agent_file_chunks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content_text LONGTEXT NOT NULL,
  metadata_json TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_agent_file_chunks_file (file_id),
  INDEX idx_agent_file_chunks_session (user_id, session_id)
);
