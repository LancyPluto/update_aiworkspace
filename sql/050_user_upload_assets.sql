CREATE TABLE IF NOT EXISTS user_upload_assets (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  file_id VARCHAR(64) NOT NULL,
  asset_kind VARCHAR(16) NOT NULL DEFAULT 'file',
  original_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NULL,
  file_size BIGINT NULL,
  url VARCHAR(1024) NOT NULL,
  storage_path VARCHAR(1024) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_upload_assets_user_kind (user_id, asset_kind, status, id),
  KEY idx_user_upload_assets_user_url (user_id, url(255))
);
