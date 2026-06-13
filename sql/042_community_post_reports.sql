-- Community post user reports for moderation.

CREATE TABLE IF NOT EXISTS community_post_reports (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  reporter_user_id BIGINT NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  admin_note VARCHAR(500) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_community_post_reports_user (post_id, reporter_user_id),
  KEY idx_community_post_reports_status_created (status, created_at, id),
  KEY idx_community_post_reports_post (post_id, status, id)
);
