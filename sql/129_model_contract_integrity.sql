-- A model contract cannot be READY when its request schema is malformed.
-- Keep the provider mappings and credentials intact; DOCS_PENDING makes the
-- task runtime use the provider's native parameter contract until an admin
-- republishes a validated schema.
UPDATE agent_model_configs
SET contract_status = 'DOCS_PENDING',
    updated_at = CURRENT_TIMESTAMP
WHERE contract_status = 'READY'
  AND request_schema_json IS NOT NULL
  AND TRIM(request_schema_json) <> ''
  AND JSON_VALID(request_schema_json) = 0;
