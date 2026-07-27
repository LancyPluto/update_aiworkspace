SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- openai_gateway described a protocol-compatible access path, not the
-- credential issuer. Re-home each account by its explicit API host. Unknown
-- hosts abort the whole transaction so production ownership is never guessed.
-- Temporary tables contain numeric IDs only to avoid collation drift.
DROP PROCEDURE IF EXISTS normalize_openai_gateway_vendors_129;

DELIMITER $$
CREATE PROCEDURE normalize_openai_gateway_vendors_129()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;

  DROP TEMPORARY TABLE IF EXISTS tmp_gateway_legacy_pools_129;
  DROP TEMPORARY TABLE IF EXISTS tmp_gateway_account_targets_129;
  DROP TEMPORARY TABLE IF EXISTS tmp_gateway_pool_account_targets_129;
  DROP TEMPORARY TABLE IF EXISTS tmp_gateway_pool_targets_129;
  DROP TEMPORARY TABLE IF EXISTS tmp_gateway_pool_redirects_129;

  CREATE TEMPORARY TABLE tmp_gateway_legacy_pools_129 (
    source_pool_id BIGINT PRIMARY KEY
  ) ENGINE=InnoDB;

  INSERT INTO tmp_gateway_legacy_pools_129(source_pool_id)
  SELECT id
  FROM model_account_routing_pools
  WHERE vendor_code = 'openai_gateway';

  -- target_kind: 1 = OpenAI, 2 = oFox. The regular expressions require an
  -- explicit HTTP(S) host and a domain boundary; look-alike suffixes fail.
  CREATE TEMPORARY TABLE tmp_gateway_account_targets_129 (
    account_id BIGINT PRIMARY KEY,
    target_kind TINYINT NOT NULL,
    source_pool_id BIGINT NULL
  ) ENGINE=InnoDB;

  INSERT INTO tmp_gateway_account_targets_129(account_id, target_kind, source_pool_id)
  SELECT account.id,
         CASE
           WHEN LOWER(TRIM(account.base_url))
                  REGEXP '^https?://api[.]openai[.]com([:/?#]|$)' THEN 1
           WHEN LOWER(TRIM(account.base_url))
                  REGEXP '^https?://([a-z0-9-]+[.])*ofox[.]ai([:/?#]|$)' THEN 2
         END,
         account.routing_pool_id
  FROM model_vendor_accounts account
  WHERE account.vendor_code = 'openai_gateway'
    AND (
      LOWER(TRIM(account.base_url))
        REGEXP '^https?://api[.]openai[.]com([:/?#]|$)'
      OR LOWER(TRIM(account.base_url))
        REGEXP '^https?://([a-z0-9-]+[.])*ofox[.]ai([:/?#]|$)'
    );

  IF EXISTS (
    SELECT 1
    FROM model_vendor_accounts account
    LEFT JOIN tmp_gateway_account_targets_129 target
      ON target.account_id = account.id
    WHERE account.vendor_code = 'openai_gateway'
      AND target.account_id IS NULL
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration blocked: unknown account host; classify it manually';
  END IF;

  -- A previously partially normalized account may already point at a matching
  -- destination pool. Missing pools and cross-vendor pools are unsafe.
  IF EXISTS (
    SELECT 1
    FROM tmp_gateway_account_targets_129 target
    LEFT JOIN model_account_routing_pools pool
      ON pool.id = target.source_pool_id
    WHERE target.source_pool_id IS NOT NULL
      AND (
        pool.id IS NULL
        OR NOT (
          pool.vendor_code = 'openai_gateway'
          OR (target.target_kind = 1 AND pool.vendor_code = 'openai')
          OR (target.target_kind = 2 AND pool.vendor_code = 'ofox')
        )
      )
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration blocked: account routing pool is missing or mismatched';
  END IF;

  -- A model with routing enabled must keep the same pool as its anchor account.
  -- Check every model linked to an account being migrated, including models
  -- already attached to a destination pool.
  IF EXISTS (
    SELECT 1
    FROM agent_model_configs model
    JOIN tmp_gateway_account_targets_129 target
      ON target.account_id = model.vendor_account_id
    JOIN model_vendor_accounts account
      ON account.id = target.account_id
    LEFT JOIN model_account_routing_pools pool
      ON pool.id = model.routing_pool_id
    WHERE model.routing_pool_id IS NOT NULL
      AND (
        account.routing_pool_id IS NULL
        OR account.routing_pool_id <> model.routing_pool_id
        OR pool.id IS NULL
        OR NOT (
          pool.vendor_code = 'openai_gateway'
          OR (target.target_kind = 1 AND pool.vendor_code = 'openai')
          OR (target.target_kind = 2 AND pool.vendor_code = 'ofox')
        )
      )
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration blocked: linked model routing pool is invalid';
  END IF;

  -- Capture every account currently attached to a legacy pool. Existing
  -- openai/ofox rows support an idempotent recovery from manual partial work.
  CREATE TEMPORARY TABLE tmp_gateway_pool_account_targets_129 (
    account_id BIGINT PRIMARY KEY,
    source_pool_id BIGINT NOT NULL,
    target_kind TINYINT NOT NULL
  ) ENGINE=InnoDB;

  INSERT INTO tmp_gateway_pool_account_targets_129(account_id, source_pool_id, target_kind)
  SELECT target.account_id, target.source_pool_id, target.target_kind
  FROM tmp_gateway_account_targets_129 target
  JOIN tmp_gateway_legacy_pools_129 legacy
    ON legacy.source_pool_id = target.source_pool_id;

  -- MySQL cannot reopen the same temporary table from two UNION branches in
  -- one statement. Keep this as a separate insert from the legacy-pool read
  -- above so the migration remains executable on MySQL 8.
  INSERT INTO tmp_gateway_pool_account_targets_129(account_id, source_pool_id, target_kind)
  SELECT account.id,
         account.routing_pool_id,
         CASE WHEN account.vendor_code = 'openai' THEN 1 ELSE 2 END
  FROM model_vendor_accounts account
  JOIN tmp_gateway_legacy_pools_129 legacy
    ON legacy.source_pool_id = account.routing_pool_id
  WHERE account.vendor_code IN ('openai', 'ofox');

  IF EXISTS (
    SELECT 1
    FROM model_vendor_accounts account
    JOIN tmp_gateway_legacy_pools_129 legacy
      ON legacy.source_pool_id = account.routing_pool_id
    LEFT JOIN tmp_gateway_pool_account_targets_129 target
      ON target.account_id = account.id
    WHERE target.account_id IS NULL
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration blocked: legacy pool has an unsupported account';
  END IF;

  -- Pool-only models and dangling/mismatched anchor accounts cannot determine
  -- which vendor owns a mixed or orphaned legacy pool, so fail closed.
  IF EXISTS (
    SELECT 1
    FROM agent_model_configs model
    JOIN tmp_gateway_legacy_pools_129 legacy
      ON legacy.source_pool_id = model.routing_pool_id
    LEFT JOIN model_vendor_accounts account
      ON account.id = model.vendor_account_id
    LEFT JOIN tmp_gateway_pool_account_targets_129 target
      ON target.account_id = model.vendor_account_id
     AND target.source_pool_id = model.routing_pool_id
    WHERE model.vendor_account_id IS NULL
       OR account.id IS NULL
       OR target.account_id IS NULL
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration blocked: legacy pool model has no matching anchor account';
  END IF;

  CREATE TEMPORARY TABLE tmp_gateway_pool_targets_129 (
    source_pool_id BIGINT NOT NULL,
    target_kind TINYINT NOT NULL,
    PRIMARY KEY (source_pool_id, target_kind)
  ) ENGINE=InnoDB;

  INSERT INTO tmp_gateway_pool_targets_129(source_pool_id, target_kind)
  SELECT DISTINCT source_pool_id, target_kind
  FROM tmp_gateway_pool_account_targets_129;

  INSERT INTO model_vendors(vendor_code, vendor_label, icon_asset, sort_order, enabled)
  VALUES ('ofox', 'oFox', 'ofox', 70, 1)
  ON DUPLICATE KEY UPDATE
    vendor_label = VALUES(vendor_label),
    icon_asset = VALUES(icon_asset),
    sort_order = VALUES(sort_order),
    enabled = 1,
    updated_at = CURRENT_TIMESTAMP;

  -- Create a destination per vendor and pool key. Existing same-key pools win;
  -- subsequent redirects use their numeric IDs.
  INSERT INTO model_account_routing_pools(
    vendor_code, pool_name, pool_key, created_at, updated_at
  )
  SELECT CASE WHEN target.target_kind = 1 THEN 'openai' ELSE 'ofox' END,
         source.pool_name,
         source.pool_key,
         CURRENT_TIMESTAMP,
         CURRENT_TIMESTAMP
  FROM tmp_gateway_pool_targets_129 target
  JOIN model_account_routing_pools source
    ON source.id = target.source_pool_id
  LEFT JOIN model_account_routing_pools destination
    ON destination.pool_key = source.pool_key
   AND (
     (target.target_kind = 1 AND destination.vendor_code = 'openai')
     OR (target.target_kind = 2 AND destination.vendor_code = 'ofox')
   )
  WHERE destination.id IS NULL;

  CREATE TEMPORARY TABLE tmp_gateway_pool_redirects_129 (
    source_pool_id BIGINT NOT NULL,
    target_kind TINYINT NOT NULL,
    target_pool_id BIGINT NOT NULL,
    PRIMARY KEY (source_pool_id, target_kind)
  ) ENGINE=InnoDB;

  INSERT INTO tmp_gateway_pool_redirects_129(source_pool_id, target_kind, target_pool_id)
  SELECT target.source_pool_id, target.target_kind, destination.id
  FROM tmp_gateway_pool_targets_129 target
  JOIN model_account_routing_pools source
    ON source.id = target.source_pool_id
  JOIN model_account_routing_pools destination
    ON destination.pool_key = source.pool_key
   AND (
     (target.target_kind = 1 AND destination.vendor_code = 'openai')
     OR (target.target_kind = 2 AND destination.vendor_code = 'ofox')
   );

  IF EXISTS (
    SELECT 1
    FROM tmp_gateway_pool_targets_129 target
    LEFT JOIN tmp_gateway_pool_redirects_129 redirect
      ON redirect.source_pool_id = target.source_pool_id
     AND redirect.target_kind = target.target_kind
    WHERE redirect.target_pool_id IS NULL
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration failed: destination routing pool is missing';
  END IF;

  UPDATE model_vendor_accounts account
  JOIN tmp_gateway_pool_account_targets_129 target
    ON target.account_id = account.id
  JOIN tmp_gateway_pool_redirects_129 redirect
    ON redirect.source_pool_id = target.source_pool_id
   AND redirect.target_kind = target.target_kind
  SET account.routing_pool_id = redirect.target_pool_id,
      account.updated_at = CURRENT_TIMESTAMP;

  UPDATE agent_model_configs model
  JOIN tmp_gateway_pool_account_targets_129 target
    ON target.account_id = model.vendor_account_id
   AND target.source_pool_id = model.routing_pool_id
  JOIN tmp_gateway_pool_redirects_129 redirect
    ON redirect.source_pool_id = target.source_pool_id
   AND redirect.target_kind = target.target_kind
  SET model.routing_pool_id = redirect.target_pool_id,
      model.updated_at = CURRENT_TIMESTAMP;

  UPDATE model_vendor_accounts account
  JOIN tmp_gateway_account_targets_129 target
    ON target.account_id = account.id
  SET account.vendor_code = CASE
        WHEN target.target_kind = 1 THEN 'openai'
        ELSE 'ofox'
      END,
      account.updated_at = CURRENT_TIMESTAMP;

  DELETE pool
  FROM model_account_routing_pools pool
  JOIN tmp_gateway_legacy_pools_129 legacy
    ON legacy.source_pool_id = pool.id;

  IF EXISTS (
    SELECT 1
    FROM model_vendor_accounts account
    WHERE account.vendor_code = 'openai_gateway'
  ) OR EXISTS (
    SELECT 1
    FROM model_account_routing_pools pool
    WHERE pool.vendor_code = 'openai_gateway'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration failed: legacy vendor_code remains';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM model_vendor_accounts account
    JOIN tmp_gateway_legacy_pools_129 legacy
      ON legacy.source_pool_id = account.routing_pool_id
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration failed: legacy account routing pool reference remains';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM agent_model_configs model
    JOIN tmp_gateway_legacy_pools_129 legacy
      ON legacy.source_pool_id = model.routing_pool_id
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration failed: legacy model routing pool reference remains';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM agent_model_configs model
    JOIN tmp_gateway_account_targets_129 target
      ON target.account_id = model.vendor_account_id
    LEFT JOIN model_vendor_accounts account
      ON account.id = model.vendor_account_id
    LEFT JOIN model_account_routing_pools pool
      ON pool.id = model.routing_pool_id
    WHERE account.id IS NULL
       OR NOT (
         (target.target_kind = 1 AND account.vendor_code = 'openai')
         OR (target.target_kind = 2 AND account.vendor_code = 'ofox')
       )
       OR (
         model.routing_pool_id IS NOT NULL
         AND (
           account.routing_pool_id IS NULL
           OR account.routing_pool_id <> model.routing_pool_id
           OR pool.id IS NULL
           OR NOT (
             (target.target_kind = 1 AND pool.vendor_code = 'openai')
             OR (target.target_kind = 2 AND pool.vendor_code = 'ofox')
           )
         )
       )
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'gateway vendor migration failed: model account or pool reference is inconsistent';
  END IF;

  UPDATE model_vendors
  SET enabled = 0,
      updated_at = CURRENT_TIMESTAMP
  WHERE vendor_code = 'openai_gateway';

  DROP TEMPORARY TABLE tmp_gateway_pool_redirects_129;
  DROP TEMPORARY TABLE tmp_gateway_pool_targets_129;
  DROP TEMPORARY TABLE tmp_gateway_pool_account_targets_129;
  DROP TEMPORARY TABLE tmp_gateway_account_targets_129;
  DROP TEMPORARY TABLE tmp_gateway_legacy_pools_129;
  COMMIT;
END $$
DELIMITER ;

CALL normalize_openai_gateway_vendors_129();
DROP PROCEDURE IF EXISTS normalize_openai_gateway_vendors_129;
