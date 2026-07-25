-- PPT workspace V2: immutable deck and slide version records.

CREATE TABLE IF NOT EXISTS ppt_deck_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  source_job_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  title VARCHAR(255) NULL,
  content_spec_json JSON NULL,
  design_spec_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ppt_deck_version (project_id, version_no),
  KEY idx_ppt_deck_project_created (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ppt_slides (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  deck_version_id BIGINT NOT NULL,
  slide_no INT NOT NULL,
  title VARCHAR(255) NULL,
  engine_page_id VARCHAR(128) NULL,
  content_json JSON NULL,
  preview_url VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ppt_slide_position (deck_version_id, slide_no),
  KEY idx_ppt_slide_project (project_id, deck_version_id),
  KEY idx_ppt_slide_engine_page (project_id, engine_page_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ppt_slide_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  slide_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  source_job_id BIGINT NULL,
  content_json JSON NULL,
  preview_url VARCHAR(1024) NULL,
  conversion_mode VARCHAR(32) NOT NULL DEFAULT 'RASTERIZED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ppt_slide_version (slide_id, version_no),
  KEY idx_ppt_slide_version_created (slide_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
