SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS guard_removed_retrieval_capabilities;

DELIMITER $$
CREATE PROCEDURE guard_removed_retrieval_capabilities()
BEGIN
  DECLARE legacy_reference_count BIGINT DEFAULT 0;

  SELECT COUNT(*)
  INTO legacy_reference_count
  FROM (
    SELECT id
    FROM ai_tools
    WHERE UPPER(COALESCE(tool_type, '')) IN ('EMBEDDING', 'RERANK')
       OR UPPER(COALESCE(execution_handler, '')) IN ('EMBEDDING', 'RERANK')
       OR UPPER(COALESCE(required_model_capabilities, ''))
            REGEXP '(^|[^A-Z_])(EMBEDDING|RERANK)([^A-Z_]|$)'

    UNION ALL

    SELECT id
    FROM tool_templates
    WHERE UPPER(COALESCE(tool_type, '')) IN ('EMBEDDING', 'RERANK')
       OR UPPER(COALESCE(execution_handler, '')) IN ('EMBEDDING', 'RERANK')
       OR UPPER(COALESCE(CAST(handler_config_json AS CHAR), ''))
            REGEXP '(^|[^A-Z_])(EMBEDDING|RERANK)([^A-Z_]|$)'

    UNION ALL

    SELECT id
    FROM agent_model_configs
    WHERE UPPER(COALESCE(capabilities, ''))
            REGEXP '(^|[^A-Z_])(EMBEDDING|RERANK)([^A-Z_]|$)'

    UNION ALL

    SELECT id
    FROM model_provider_metadata
    WHERE UPPER(COALESCE(capabilities_json, ''))
            REGEXP '(^|[^A-Z_])(EMBEDDING|RERANK)([^A-Z_]|$)'
       OR UPPER(COALESCE(model_param_schema_json, ''))
            REGEXP '(^|[^A-Z_])(EMBEDDING|RERANK)([^A-Z_]|$)'
  ) legacy_references;

  IF legacy_reference_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'EMBEDDING/RERANK removal blocked: active configuration references remain';
  END IF;
END $$
DELIMITER ;

CALL guard_removed_retrieval_capabilities();
DROP PROCEDURE IF EXISTS guard_removed_retrieval_capabilities;
