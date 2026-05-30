ALTER TABLE community_posts
  ADD COLUMN IF NOT EXISTS detail_click_count BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS share_count BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS quality_score BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_featured_at DATETIME NULL;

CREATE INDEX IF NOT EXISTS idx_community_posts_status_topic_id
  ON community_posts(status, topic, id);

CREATE INDEX IF NOT EXISTS idx_community_posts_status_modality_id
  ON community_posts(status, modality, id);

CREATE INDEX IF NOT EXISTS idx_community_posts_quality
  ON community_posts(status, audit_status, pinned, featured, quality_score, id);

CREATE TABLE IF NOT EXISTS community_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NULL,
  user_id BIGINT NULL,
  event_type VARCHAR(48) NOT NULL,
  source VARCHAR(64) NULL,
  tool_code VARCHAR(128) NULL,
  task_id BIGINT NULL,
  credits INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_community_events_type_created (event_type, created_at),
  KEY idx_community_events_post_type (post_id, event_type),
  KEY idx_community_events_tool (tool_code, event_type)
);

CREATE TABLE IF NOT EXISTS community_collections (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(80) NOT NULL,
  default_collection TINYINT NOT NULL DEFAULT 0,
  item_count BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_community_collections_user (user_id, id),
  KEY idx_community_default_collection (user_id, default_collection)
);

CREATE TABLE IF NOT EXISTS community_collection_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  collection_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_community_collection_item (collection_id, post_id),
  KEY idx_community_collection_items_user (user_id, collection_id, id)
);
