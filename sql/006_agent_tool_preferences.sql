SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS agent_tool_preferences (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  auto_call_enabled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_tool_pref_user_tool (user_id, tool_code),
  KEY idx_agent_tool_pref_user (user_id)
);

