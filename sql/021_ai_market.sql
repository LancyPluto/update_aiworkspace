SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS ai_market_tools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL,
  icon_url VARCHAR(512) NOT NULL,
  description VARCHAR(256) NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  primary_color VARCHAR(16) NULL,
  welcome_message TEXT NULL,
  capabilities_json JSON NOT NULL,
  model_config_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_market_tools_enabled_order (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_market_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  tool_id VARCHAR(64) NOT NULL,
  title VARCHAR(128) NOT NULL DEFAULT '新对话',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_market_sessions_user_tool (user_id, tool_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_market_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id VARCHAR(64) NOT NULL UNIQUE,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  params_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_market_messages_session (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_market_message_attachments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id VARCHAR(64) NOT NULL,
  file_id VARCHAR(64) NOT NULL,
  file_name VARCHAR(256) NOT NULL,
  file_size BIGINT NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  KEY idx_market_msg_attach_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_market_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  tool_id VARCHAR(64) NULL,
  original_name VARCHAR(256) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  file_size BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_market_files_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ai_market_tools (tool_id, name, icon_url, description, enabled, sort_order, primary_color, welcome_message, capabilities_json)
VALUES
(
  'doubao',
  '豆包',
  '/generated/icons/doubao.png',
  '生图 + 文件阅读，适合日常创作',
  1,
  10,
  '#f97316',
  '你好，我是豆包~ 有什么可以帮你？',
  JSON_ARRAY(
    JSON_OBJECT('type', 'imageGeneration', 'config', JSON_OBJECT('aspectRatios', JSON_ARRAY('1:1', '16:9', '9:16'), 'defaultRatio', '1:1', 'maxImagesPerRequest', 1)),
    JSON_OBJECT('type', 'fileReading', 'config', JSON_OBJECT('supportedFileTypes', JSON_ARRAY('pdf', 'txt', 'png'), 'maxSizeMB', 20))
  )
),
(
  'wenxin',
  '文心一言',
  '/generated/icons/wenxin.png',
  '联网搜索 + 代码执行',
  1,
  20,
  '#3b82f6',
  '你好，我是文心一言，很高兴为你服务。',
  JSON_ARRAY(
    JSON_OBJECT('type', 'webSearch', 'config', JSON_OBJECT('enabled', true, 'defaultEnabled', false)),
    JSON_OBJECT('type', 'codeExecution', 'config', JSON_OBJECT('supportedLanguages', JSON_ARRAY('python', 'javascript')))
  )
)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  icon_url = VALUES(icon_url),
  description = VALUES(description),
  enabled = VALUES(enabled),
  sort_order = VALUES(sort_order),
  primary_color = VALUES(primary_color),
  welcome_message = VALUES(welcome_message),
  capabilities_json = VALUES(capabilities_json),
  updated_at = CURRENT_TIMESTAMP;
