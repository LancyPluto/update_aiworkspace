SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Routing pool identity is (vendor_code, pool_key), where pool_key is the
-- normalized pool name. Merge aliases before changing vendor_code so the
-- unique key is never violated. Compare pool_key through two aliases of the
-- same production column so differing server/default collations cannot leak
-- into a temporary string column. Existing suno pools always survive.
-- Model providers remain unchanged because suno_music identifies the adapter.
DROP PROCEDURE IF EXISTS normalize_suno_vendor_codes_127;

DELIMITER $$
CREATE PROCEDURE normalize_suno_vendor_codes_127()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;

  DROP TEMPORARY TABLE IF EXISTS tmp_suno_pool_redirects_127;
  CREATE TEMPORARY TABLE tmp_suno_pool_redirects_127 (
    source_pool_id BIGINT PRIMARY KEY,
    target_pool_id BIGINT NOT NULL
  ) ENGINE=InnoDB;

  INSERT INTO tmp_suno_pool_redirects_127(source_pool_id, target_pool_id)
  SELECT legacy.id, canonical.id
  FROM model_account_routing_pools legacy
  JOIN model_account_routing_pools canonical
    ON canonical.vendor_code = 'suno'
   AND canonical.pool_key = legacy.pool_key
  WHERE legacy.vendor_code = 'suno_music';

  UPDATE model_vendor_accounts account
  JOIN tmp_suno_pool_redirects_127 redirect
    ON redirect.source_pool_id = account.routing_pool_id
  SET account.routing_pool_id = redirect.target_pool_id,
      account.updated_at = CURRENT_TIMESTAMP;

  UPDATE agent_model_configs model
  JOIN tmp_suno_pool_redirects_127 redirect
    ON redirect.source_pool_id = model.routing_pool_id
  SET model.routing_pool_id = redirect.target_pool_id,
      model.updated_at = CURRENT_TIMESTAMP;

  DELETE pool
  FROM model_account_routing_pools pool
  JOIN tmp_suno_pool_redirects_127 redirect
    ON redirect.source_pool_id = pool.id;

  UPDATE model_account_routing_pools
  SET vendor_code = 'suno',
      updated_at = CURRENT_TIMESTAMP
  WHERE vendor_code = 'suno_music';

  UPDATE model_vendor_accounts
  SET vendor_code = 'suno',
      updated_at = CURRENT_TIMESTAMP
  WHERE vendor_code = 'suno_music';

  IF EXISTS (
    SELECT 1
    FROM model_vendor_accounts
    WHERE vendor_code = 'suno_music'
  ) OR EXISTS (
    SELECT 1
    FROM model_account_routing_pools
    WHERE vendor_code = 'suno_music'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'suno vendor normalization failed: legacy vendor_code remains';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM model_account_routing_pools
    WHERE vendor_code = 'suno'
    GROUP BY pool_key
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'suno vendor normalization failed: duplicate suno routing pool key';
  END IF;

  DROP TEMPORARY TABLE tmp_suno_pool_redirects_127;
  COMMIT;
END $$
DELIMITER ;

CALL normalize_suno_vendor_codes_127();
DROP PROCEDURE IF EXISTS normalize_suno_vendor_codes_127;
