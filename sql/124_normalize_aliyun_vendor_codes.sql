SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Routing pool identity is (vendor_code, pool_key), where pool_key is the
-- normalized pool name. Merge aliases before changing vendor_code so the
-- unique key is never violated. Prefer an existing qwen pool; otherwise keep
-- the oldest alias pool and redirect every account/model reference to it.
DROP PROCEDURE IF EXISTS normalize_aliyun_vendor_codes_124;

DELIMITER $$
CREATE PROCEDURE normalize_aliyun_vendor_codes_124()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;

  DROP TEMPORARY TABLE IF EXISTS tmp_aliyun_pool_redirects_124;
  DROP TEMPORARY TABLE IF EXISTS tmp_aliyun_pool_survivors_124;

  CREATE TEMPORARY TABLE tmp_aliyun_pool_survivors_124 (
    pool_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY,
    target_pool_id BIGINT NOT NULL
  ) ENGINE=InnoDB;

  INSERT INTO tmp_aliyun_pool_survivors_124(pool_key, target_pool_id)
  SELECT pool_key,
         COALESCE(
           MIN(CASE WHEN vendor_code = 'qwen' THEN id END),
           MIN(id)
         ) AS target_pool_id
  FROM model_account_routing_pools
  WHERE vendor_code IN ('qwen', 'bailian_happyhorse', 'dashscope', 'aliyun_bailian')
  GROUP BY pool_key;

  CREATE TEMPORARY TABLE tmp_aliyun_pool_redirects_124 (
    source_pool_id BIGINT PRIMARY KEY,
    target_pool_id BIGINT NOT NULL
  ) ENGINE=InnoDB;

  INSERT INTO tmp_aliyun_pool_redirects_124(source_pool_id, target_pool_id)
  SELECT pool.id, survivor.target_pool_id
  FROM model_account_routing_pools pool
  JOIN tmp_aliyun_pool_survivors_124 survivor
    ON survivor.pool_key = pool.pool_key
  WHERE pool.vendor_code IN ('bailian_happyhorse', 'dashscope', 'aliyun_bailian')
    AND pool.id <> survivor.target_pool_id;

  UPDATE model_vendor_accounts account
  JOIN tmp_aliyun_pool_redirects_124 redirect
    ON redirect.source_pool_id = account.routing_pool_id
  SET account.routing_pool_id = redirect.target_pool_id,
      account.updated_at = CURRENT_TIMESTAMP;

  UPDATE agent_model_configs model
  JOIN tmp_aliyun_pool_redirects_124 redirect
    ON redirect.source_pool_id = model.routing_pool_id
  SET model.routing_pool_id = redirect.target_pool_id,
      model.updated_at = CURRENT_TIMESTAMP;

  DELETE pool
  FROM model_account_routing_pools pool
  JOIN tmp_aliyun_pool_redirects_124 redirect
    ON redirect.source_pool_id = pool.id;

  UPDATE model_account_routing_pools
  SET vendor_code = 'qwen',
      updated_at = CURRENT_TIMESTAMP
  WHERE vendor_code IN ('bailian_happyhorse', 'dashscope', 'aliyun_bailian');

  UPDATE model_vendor_accounts
  SET vendor_code = 'qwen',
      updated_at = CURRENT_TIMESTAMP
  WHERE vendor_code IN ('bailian_happyhorse', 'dashscope', 'aliyun_bailian');

  IF EXISTS (
    SELECT 1
    FROM model_vendor_accounts
    WHERE vendor_code IN ('bailian_happyhorse', 'dashscope', 'aliyun_bailian')
  ) OR EXISTS (
    SELECT 1
    FROM model_account_routing_pools
    WHERE vendor_code IN ('bailian_happyhorse', 'dashscope', 'aliyun_bailian')
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'aliyun vendor normalization failed: legacy vendor_code remains';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM model_account_routing_pools
    WHERE vendor_code = 'qwen'
    GROUP BY pool_key
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'aliyun vendor normalization failed: duplicate qwen routing pool key';
  END IF;

  DROP TEMPORARY TABLE tmp_aliyun_pool_redirects_124;
  DROP TEMPORARY TABLE tmp_aliyun_pool_survivors_124;
  COMMIT;
END $$
DELIMITER ;

CALL normalize_aliyun_vendor_codes_124();
DROP PROCEDURE IF EXISTS normalize_aliyun_vendor_codes_124;
