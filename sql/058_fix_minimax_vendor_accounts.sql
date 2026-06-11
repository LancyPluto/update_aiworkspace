SET NAMES utf8mb4;

-- 删除多余 MiniMax 账户（无模型、无凭据的重复账户）
UPDATE model_vendor_accounts
SET is_deleted = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE vendor_code = 'minimax'
  AND is_deleted = 0
  AND id NOT IN (
    SELECT vendor_account_id FROM (
      SELECT vendor_account_id
      FROM agent_model_configs
      WHERE is_deleted = 0
        AND vendor_account_id IS NOT NULL
      GROUP BY vendor_account_id
      HAVING COUNT(*) > 0
    ) linked
  );

-- MiniMax 无稳定 REST 余额接口，避免误报「查询失败 / 连接异常」
UPDATE model_vendor_accounts
SET balance_query_mode = 'NONE',
    balance_error_message = NULL,
    balance_status = 'UNKNOWN',
    updated_at = CURRENT_TIMESTAMP
WHERE vendor_code = 'minimax'
  AND is_deleted = 0
  AND balance_query_mode = 'REST_API';
