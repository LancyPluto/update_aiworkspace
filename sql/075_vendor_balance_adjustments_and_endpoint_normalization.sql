SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS vendor_balance_adjustments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  billing_usage_log_id BIGINT NOT NULL,
  vendor_account_id BIGINT NOT NULL,
  balance_before DECIMAL(18,6) NOT NULL,
  balance_after DECIMAL(18,6) NOT NULL,
  deducted_amount DECIMAL(18,6) NOT NULL,
  balance_currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_vendor_balance_adjustment_log (billing_usage_log_id),
  KEY idx_vendor_balance_adjustment_account (vendor_account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Normalize accidentally saved OpenAI-compatible full endpoints into protocol roots.
UPDATE model_vendor_accounts
SET extra_auth_json = JSON_SET(COALESCE(NULLIF(extra_auth_json, ''), '{}'), '$.endpointPath', '/images/generations'),
    base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/images/generations')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/images/generations';

UPDATE agent_model_configs
SET extra_auth_json = JSON_SET(COALESCE(NULLIF(extra_auth_json, ''), '{}'), '$.endpointPath', '/images/generations'),
    base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/images/generations')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/images/generations';

UPDATE model_vendor_accounts
SET extra_auth_json = JSON_SET(COALESCE(NULLIF(extra_auth_json, ''), '{}'), '$.endpointPath', '/images/edits'),
    base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/images/edits')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/images/edits';

UPDATE agent_model_configs
SET extra_auth_json = JSON_SET(COALESCE(NULLIF(extra_auth_json, ''), '{}'), '$.endpointPath', '/images/edits'),
    base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/images/edits')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/images/edits';

UPDATE model_vendor_accounts
SET extra_auth_json = JSON_SET(COALESCE(NULLIF(extra_auth_json, ''), '{}'), '$.endpointPath', '/chat/completions'),
    base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/chat/completions')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/chat/completions';

UPDATE agent_model_configs
SET extra_auth_json = JSON_SET(COALESCE(NULLIF(extra_auth_json, ''), '{}'), '$.endpointPath', '/chat/completions'),
    base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/chat/completions')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/chat/completions';

UPDATE model_vendor_accounts
SET base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/models')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/models';

UPDATE agent_model_configs
SET base_url = LEFT(TRIM(TRAILING '/' FROM base_url), CHAR_LENGTH(TRIM(TRAILING '/' FROM base_url)) - CHAR_LENGTH('/models')),
    updated_at = NOW()
WHERE base_url IS NOT NULL
  AND LOWER(TRIM(TRAILING '/' FROM base_url)) LIKE '%/models';

-- OpenAI-compatible relays usually do not expose a standard balance API.
-- Existing OpenAI accounts with locally filled balances should use manual mode so billing can deduct them.
UPDATE model_vendor_accounts
SET balance_query_mode = 'MANUAL',
    balance_error_message = NULL,
    balance_status = CASE
        WHEN balance_amount IS NULL THEN 'UNKNOWN'
        WHEN balance_low_threshold IS NOT NULL AND balance_amount < balance_low_threshold THEN 'LOW'
        ELSE 'OK'
    END,
    updated_at = NOW()
WHERE LOWER(vendor_code) IN ('openai', 'openai_gateway')
  AND balance_amount IS NOT NULL
  AND UPPER(COALESCE(balance_query_mode, '')) IN ('REST_API', 'INFERRED');
