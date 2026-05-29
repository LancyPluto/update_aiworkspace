CREATE TABLE IF NOT EXISTS system_setting_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  setting_key VARCHAR(128) NOT NULL,
  setting_value TEXT,
  operator_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_system_setting_versions_key (setting_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
