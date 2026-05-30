-- Community V1: public profile posts and user publish preferences.
-- This script is intentionally idempotent so it can be applied to an existing
-- local database after the Docker init phase has already run.

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(
  IN table_name_in VARCHAR(64),
  IN column_name_in VARCHAR(64),
  IN ddl_in TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = table_name_in
      AND column_name = column_name_in
  ) THEN
    SET @ddl = ddl_in;
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_missing('users', 'bio', 'ALTER TABLE users ADD COLUMN bio VARCHAR(280) NULL');
CALL add_column_if_missing('users', 'auto_publish_assets', 'ALTER TABLE users ADD COLUMN auto_publish_assets TINYINT NOT NULL DEFAULT 1');
CALL add_column_if_missing('users', 'prompt_public_by_default', 'ALTER TABLE users ADD COLUMN prompt_public_by_default TINYINT NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS add_column_if_missing;

CREATE TABLE IF NOT EXISTS community_posts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  modality VARCHAR(32) NOT NULL,
  cover_url VARCHAR(1024),
  title VARCHAR(160) NOT NULL,
  description VARCHAR(500),
  prompt_visible TINYINT NOT NULL DEFAULT 0,
  prompt_snapshot MEDIUMTEXT,
  tool_code VARCHAR(128),
  tool_name VARCHAR(128),
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
  view_count BIGINT NOT NULL DEFAULT 0,
  like_count BIGINT NOT NULL DEFAULT 0,
  favorite_count BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_community_posts_task (task_id),
  INDEX idx_community_posts_user_status_id (user_id, status, id),
  INDEX idx_community_posts_status_id (status, id)
);

CREATE TABLE IF NOT EXISTS community_post_likes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_community_post_likes_user (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS community_post_favorites (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_community_post_favorites_user (post_id, user_id)
);
