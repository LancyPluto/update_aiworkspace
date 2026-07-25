-- PPT workspace V2: platform-owned projects and engine bindings.
-- Additive only; legacy ppt_project_bindings remains the compatibility source.

CREATE TABLE IF NOT EXISTS ppt_projects (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NULL,
  title VARCHAR(255) NOT NULL,
  topic TEXT NULL,
  creation_type VARCHAR(32) NOT NULL DEFAULT 'idea',
  language VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
  aspect_ratio VARCHAR(16) NOT NULL DEFAULT '16:9',
  page_count INT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  engine_strategy VARCHAR(32) NOT NULL DEFAULT 'VISUAL',
  current_deck_version_id BIGINT NULL,
  legacy_binding_id BIGINT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME NULL,
  UNIQUE KEY uk_ppt_project_legacy_binding (legacy_binding_id),
  KEY idx_ppt_project_user_updated (user_id, is_deleted, updated_at),
  KEY idx_ppt_project_user_status (user_id, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ppt_engine_bindings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  engine_code VARCHAR(64) NOT NULL,
  external_project_id VARCHAR(128) NOT NULL,
  engine_metadata_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ppt_engine_binding_project (project_id, engine_code),
  UNIQUE KEY uk_ppt_engine_binding_external (engine_code, external_project_id),
  KEY idx_ppt_engine_binding_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
