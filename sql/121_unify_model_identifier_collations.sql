SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- These identifiers are compared across model, vendor, audit, and callback
-- boundaries. Normalize only this domain; do not convert unrelated text columns.
-- Run every uniqueness check before the first DDL because ALTER TABLE commits.
DROP PROCEDURE IF EXISTS assert_model_identifier_unique_keys;
DROP PROCEDURE IF EXISTS normalize_model_identifier_columns;

DELIMITER $$
CREATE PROCEDURE assert_model_identifier_unique_keys()
BEGIN
  IF EXISTS (
    SELECT 1
    FROM agent_model_configs
    GROUP BY CONVERT(config_code USING utf8mb4) COLLATE utf8mb4_unicode_ci
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'model identifier collation blocked: duplicate agent model config_code';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM model_vendors
    GROUP BY CONVERT(vendor_code USING utf8mb4) COLLATE utf8mb4_unicode_ci
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'model identifier collation blocked: duplicate model vendor_code';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM model_account_routing_pools
    GROUP BY CONVERT(vendor_code USING utf8mb4) COLLATE utf8mb4_unicode_ci, pool_key
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'model identifier collation blocked: duplicate vendor routing pool key';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM workflow_step_attempts
    WHERE provider_code IS NOT NULL
      AND provider_request_id IS NOT NULL
    GROUP BY CONVERT(provider_code USING utf8mb4) COLLATE utf8mb4_unicode_ci, provider_request_id
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'model identifier collation blocked: duplicate workflow provider request';
  END IF;
END $$

CREATE PROCEDURE normalize_model_identifier_columns(
  IN target_table VARCHAR(64),
  IN target_columns VARCHAR(512)
)
BEGIN
  DECLARE expected_columns INT DEFAULT 0;
  DECLARE existing_columns INT DEFAULT 0;
  DECLARE compatible_columns INT DEFAULT 0;
  DECLARE table_default_collation VARCHAR(64) DEFAULT NULL;
  DECLARE column_clauses LONGTEXT DEFAULT NULL;
  DECLARE migration_error VARCHAR(128) DEFAULT NULL;

  SET expected_columns = 1 + CHAR_LENGTH(target_columns)
    - CHAR_LENGTH(REPLACE(target_columns, ',', ''));

  SELECT COUNT(*),
         SUM(
           data_type = 'varchar'
           AND character_set_name = 'utf8mb4'
           AND COALESCE(generation_expression, '') = ''
         )
  INTO existing_columns, compatible_columns
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = target_table
    AND FIND_IN_SET(column_name, target_columns) > 0;

  IF existing_columns <> expected_columns OR compatible_columns <> expected_columns THEN
    SET migration_error = CONCAT('model identifier columns missing or incompatible: ', target_table);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = migration_error;
  END IF;

  SELECT table_collation
  INTO table_default_collation
  FROM information_schema.tables
  WHERE table_schema = DATABASE()
    AND table_name = target_table
    AND table_type = 'BASE TABLE';

  IF table_default_collation IS NULL THEN
    SET migration_error = CONCAT('model identifier table missing: ', target_table);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = migration_error;
  END IF;

  SELECT GROUP_CONCAT(
           CONCAT(
             'MODIFY COLUMN `', column_name, '` ', column_type,
             ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
             IF(is_nullable = 'NO', ' NOT NULL', ' NULL'),
             CASE
               WHEN column_default IS NULL AND is_nullable = 'YES' THEN ' DEFAULT NULL'
               WHEN column_default IS NULL THEN ''
               WHEN extra LIKE '%DEFAULT_GENERATED%' THEN CONCAT(' DEFAULT ', column_default)
               ELSE CONCAT(' DEFAULT ', QUOTE(column_default))
             END,
             IF(extra LIKE '%INVISIBLE%', ' INVISIBLE', ''),
             ' COMMENT ', QUOTE(column_comment)
           )
           ORDER BY ordinal_position
           SEPARATOR ', '
         )
  INTO column_clauses
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = target_table
    AND FIND_IN_SET(column_name, target_columns) > 0
    AND collation_name <> 'utf8mb4_unicode_ci';

  IF table_default_collation <> 'utf8mb4_unicode_ci' OR column_clauses IS NOT NULL THEN
    SET @model_identifier_ddl = CONCAT(
      'ALTER TABLE `', target_table,
      '` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
      IF(column_clauses IS NULL, '', CONCAT(', ', column_clauses))
    );
    PREPARE model_identifier_stmt FROM @model_identifier_ddl;
    EXECUTE model_identifier_stmt;
    DEALLOCATE PREPARE model_identifier_stmt;
  END IF;

  SELECT COUNT(*)
  INTO compatible_columns
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = target_table
    AND FIND_IN_SET(column_name, target_columns) > 0
    AND character_set_name = 'utf8mb4'
    AND collation_name = 'utf8mb4_unicode_ci';

  IF compatible_columns <> expected_columns THEN
    SET migration_error = CONCAT('model identifier collation verification failed: ', target_table);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = migration_error;
  END IF;
END $$
DELIMITER ;

CALL assert_model_identifier_unique_keys();

CALL normalize_model_identifier_columns(
  'agent_model_configs', 'config_code,provider,model_name'
);
CALL normalize_model_identifier_columns(
  'model_provider_metadata', 'provider_code'
);
CALL normalize_model_identifier_columns(
  'model_vendor_accounts', 'vendor_code'
);
CALL normalize_model_identifier_columns(
  'model_vendors', 'vendor_code'
);
CALL normalize_model_identifier_columns(
  'model_account_routing_pools', 'vendor_code'
);
CALL normalize_model_identifier_columns(
  'agent_context_snapshots', 'model_provider_code,model_name'
);
CALL normalize_model_identifier_columns(
  'agent_model_request_snapshots', 'model_provider_code,model_name'
);
CALL normalize_model_identifier_columns(
  'agent_runs', 'model_provider_code,model_name'
);
CALL normalize_model_identifier_columns(
  'billing_usage_logs', 'provider,model_name'
);
CALL normalize_model_identifier_columns(
  'workflow_step_attempts', 'provider_code'
);
CALL normalize_model_identifier_columns(
  'provider_callback_registrations', 'provider_code'
);
CALL normalize_model_identifier_columns(
  'provider_callback_inbox', 'provider_code'
);
CALL normalize_model_identifier_columns(
  'user_generation_subjects', 'provider_code'
);

DROP PROCEDURE IF EXISTS assert_model_identifier_unique_keys;
DROP PROCEDURE IF EXISTS normalize_model_identifier_columns;
SET @model_identifier_ddl = NULL;
