SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS add_index_if_missing $$
CREATE PROCEDURE add_index_if_missing(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = p_index_ddl;
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL add_column_if_missing(
  'agent_messages',
  'parent_message_id',
  '`parent_message_id` BIGINT NULL COMMENT ''parent message id for tree conversation branches'' AFTER `run_id`'
);

CALL add_column_if_missing(
  'agent_sessions',
  'active_leaf_message_id',
  '`active_leaf_message_id` BIGINT NULL COMMENT ''active branch leaf message id'' AFTER `workspace_id`'
);

CALL add_index_if_missing(
  'agent_messages',
  'idx_agent_messages_parent_branch',
  'CREATE INDEX idx_agent_messages_parent_branch ON agent_messages(session_id, parent_message_id, role, id)'
);

CALL add_index_if_missing(
  'agent_sessions',
  'idx_agent_sessions_active_leaf',
  'CREATE INDEX idx_agent_sessions_active_leaf ON agent_sessions(active_leaf_message_id)'
);

UPDATE agent_messages m
JOIN (
  SELECT id,
         LAG(id) OVER (PARTITION BY session_id ORDER BY id ASC) AS parent_id
  FROM agent_messages
  WHERE status = 'ACTIVE'
) ordered_messages ON ordered_messages.id = m.id
SET m.parent_message_id = ordered_messages.parent_id
WHERE m.status = 'ACTIVE'
  AND m.parent_message_id IS NULL
  AND ordered_messages.parent_id IS NOT NULL;

UPDATE agent_sessions s
LEFT JOIN (
  SELECT session_id, MAX(id) AS leaf_id
  FROM agent_messages
  WHERE status = 'ACTIVE'
  GROUP BY session_id
) latest ON latest.session_id = s.id
SET s.active_leaf_message_id = latest.leaf_id
WHERE s.active_leaf_message_id IS NULL
  AND latest.leaf_id IS NOT NULL;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
