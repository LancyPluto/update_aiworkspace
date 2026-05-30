-- Community V2: discovery feed, curation fields, tags, and same-style metrics.

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
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
CREATE PROCEDURE add_index_if_missing(
  IN table_name_in VARCHAR(64),
  IN index_name_in VARCHAR(64),
  IN ddl_in TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = table_name_in
      AND index_name = index_name_in
  ) THEN
    SET @ddl = ddl_in;
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_missing('community_posts', 'featured', 'ALTER TABLE community_posts ADD COLUMN featured TINYINT NOT NULL DEFAULT 0');
CALL add_column_if_missing('community_posts', 'pinned', 'ALTER TABLE community_posts ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0');
CALL add_column_if_missing('community_posts', 'topic', 'ALTER TABLE community_posts ADD COLUMN topic VARCHAR(64) NULL');
CALL add_column_if_missing('community_posts', 'same_style_count', 'ALTER TABLE community_posts ADD COLUMN same_style_count BIGINT NOT NULL DEFAULT 0');
CALL add_column_if_missing('community_posts', 'audit_status', 'ALTER TABLE community_posts ADD COLUMN audit_status VARCHAR(32) NOT NULL DEFAULT ''APPROVED''');
CALL add_column_if_missing('community_posts', 'audit_reason', 'ALTER TABLE community_posts ADD COLUMN audit_reason VARCHAR(255) NULL');

DROP PROCEDURE IF EXISTS add_column_if_missing;

CREATE TABLE IF NOT EXISTS community_post_tags (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  tag VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_community_post_tags_post_tag (post_id, tag),
  INDEX idx_community_post_tags_tag_post (tag, post_id)
);

CALL add_index_if_missing('community_posts', 'idx_community_posts_discovery', 'CREATE INDEX idx_community_posts_discovery ON community_posts(status, pinned, featured, id)');
CALL add_index_if_missing('community_posts', 'idx_community_posts_modality_id', 'CREATE INDEX idx_community_posts_modality_id ON community_posts(status, modality, id)');
CALL add_index_if_missing('community_posts', 'idx_community_posts_topic_id', 'CREATE INDEX idx_community_posts_topic_id ON community_posts(status, topic, id)');
CALL add_index_if_missing('community_posts', 'idx_community_posts_popular', 'CREATE INDEX idx_community_posts_popular ON community_posts(status, like_count, favorite_count, id)');
CALL add_index_if_missing('community_posts', 'idx_community_posts_same_style', 'CREATE INDEX idx_community_posts_same_style ON community_posts(status, same_style_count, id)');

DROP PROCEDURE IF EXISTS add_index_if_missing;
