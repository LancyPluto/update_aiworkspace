SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ensure_ai_tool_required_model_capabilities;

DELIMITER $$
CREATE PROCEDURE ensure_ai_tool_required_model_capabilities()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_tools'
      AND column_name = 'required_model_capabilities'
  ) THEN
    ALTER TABLE ai_tools
      ADD COLUMN required_model_capabilities TEXT NULL AFTER execution_handler;
  END IF;
END $$
DELIMITER ;

CALL ensure_ai_tool_required_model_capabilities();
DROP PROCEDURE IF EXISTS ensure_ai_tool_required_model_capabilities;

-- Keep plugin binding slots scalar while replacing the retired model capability value.
UPDATE tool_templates
SET handler_config_json = JSON_SET(handler_config_json, '$.requiredCapability', 'VIDEO_GENERATION'),
    updated_at = CURRENT_TIMESTAMP
WHERE UPPER(COALESCE(execution_handler, '')) = 'DIGITAL_HUMAN'
  AND JSON_VALID(handler_config_json) = 1
  AND UPPER(TRIM(COALESCE(
        JSON_UNQUOTE(JSON_EXTRACT(handler_config_json, '$.requiredCapability')),
        ''
      ))) = 'DIGITAL_HUMAN';

-- Seedance records created under the image protocol cannot expose video capabilities.
UPDATE agent_model_configs
SET provider = 'seedance',
    capabilities = JSON_ARRAY('VIDEO_GENERATION'),
    execution_task = 'video_generation',
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND provider = 'volcengine_images'
  AND LOWER(model_name) LIKE '%seedance%';

-- DIGITAL_HUMAN remains an execution handler, not a model capability.
UPDATE agent_model_configs
SET capabilities = CASE
      WHEN JSON_TYPE(IF(JSON_VALID(capabilities), capabilities, JSON_OBJECT())) = 'ARRAY'
        THEN CASE
          WHEN JSON_CONTAINS(capabilities, JSON_QUOTE('VIDEO_GENERATION')) = 1
            THEN capabilities
          ELSE JSON_ARRAY_APPEND(capabilities, '$', 'VIDEO_GENERATION')
        END
      ELSE JSON_ARRAY('VIDEO_GENERATION')
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND provider IN ('seedance', 'infinitetalk')
  AND UPPER(COALESCE(capabilities, '')) LIKE '%DIGITAL_HUMAN%';

UPDATE agent_model_configs
SET capabilities = CASE
      WHEN JSON_TYPE(IF(JSON_VALID(capabilities), capabilities, JSON_OBJECT())) = 'ARRAY'
        THEN CASE
          WHEN JSON_CONTAINS(capabilities, JSON_QUOTE('IMAGE_GENERATION')) = 1
            THEN capabilities
          ELSE JSON_ARRAY_APPEND(capabilities, '$', 'IMAGE_GENERATION')
        END
      ELSE JSON_ARRAY('IMAGE_GENERATION')
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND provider IN ('siliconflow', 'siliconflow_images')
  AND UPPER(COALESCE(capabilities, '')) LIKE '%DIGITAL_HUMAN%';

UPDATE model_provider_metadata
SET capabilities_json = CASE
      WHEN JSON_TYPE(IF(JSON_VALID(capabilities_json), capabilities_json, JSON_OBJECT())) = 'ARRAY'
        THEN CASE
          WHEN JSON_CONTAINS(capabilities_json, JSON_QUOTE('VIDEO_GENERATION')) = 1
            THEN capabilities_json
          ELSE JSON_ARRAY_APPEND(capabilities_json, '$', 'VIDEO_GENERATION')
        END
      ELSE JSON_ARRAY('VIDEO_GENERATION')
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE provider_code IN ('seedance', 'infinitetalk')
  AND UPPER(COALESCE(capabilities_json, '')) LIKE '%DIGITAL_HUMAN%';

UPDATE model_provider_metadata
SET capabilities_json = CASE
      WHEN JSON_TYPE(IF(JSON_VALID(capabilities_json), capabilities_json, JSON_OBJECT())) = 'ARRAY'
        THEN CASE
          WHEN JSON_CONTAINS(capabilities_json, JSON_QUOTE('IMAGE_GENERATION')) = 1
            THEN capabilities_json
          ELSE JSON_ARRAY_APPEND(capabilities_json, '$', 'IMAGE_GENERATION')
        END
      ELSE JSON_ARRAY('IMAGE_GENERATION')
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE provider_code IN ('siliconflow', 'siliconflow_images')
  AND UPPER(COALESCE(capabilities_json, '')) LIKE '%DIGITAL_HUMAN%';

-- Rebuild valid arrays so legacy values are trimmed, upper-cased, and de-duplicated.
DROP TEMPORARY TABLE IF EXISTS normalized_agent_model_capabilities;
CREATE TEMPORARY TABLE normalized_agent_model_capabilities (
  id BIGINT PRIMARY KEY,
  capabilities JSON NOT NULL
);
INSERT INTO normalized_agent_model_capabilities (id, capabilities)
SELECT id, JSON_ARRAY()
FROM agent_model_configs
WHERE JSON_VALID(capabilities) = 1
  AND JSON_TYPE(IF(JSON_VALID(capabilities), capabilities, JSON_ARRAY())) = 'ARRAY';
UPDATE normalized_agent_model_capabilities normalized
JOIN (
  SELECT id, JSON_ARRAYAGG(capability) AS capabilities
  FROM (
    SELECT DISTINCT source.id,
           UPPER(TRIM(capability_row.raw_capability)) AS capability
    FROM agent_model_configs source
    JOIN JSON_TABLE(
      IF(
        JSON_TYPE(IF(JSON_VALID(source.capabilities), source.capabilities, JSON_ARRAY())) = 'ARRAY',
        source.capabilities,
        JSON_ARRAY()
      ),
      '$[*]' COLUMNS (
        raw_capability VARCHAR(128) PATH '$' NULL ON EMPTY NULL ON ERROR
      )
    ) capability_row ON TRUE
    WHERE JSON_VALID(source.capabilities) = 1
      AND JSON_TYPE(IF(JSON_VALID(source.capabilities), source.capabilities, JSON_ARRAY())) = 'ARRAY'
      AND capability_row.raw_capability IS NOT NULL
      AND TRIM(capability_row.raw_capability) <> ''
      AND UPPER(TRIM(capability_row.raw_capability)) <> 'DIGITAL_HUMAN'
  ) distinct_capabilities
  GROUP BY id
) rebuilt ON rebuilt.id = normalized.id
SET normalized.capabilities = rebuilt.capabilities;
UPDATE agent_model_configs target
JOIN normalized_agent_model_capabilities normalized ON normalized.id = target.id
SET target.capabilities = normalized.capabilities,
    target.updated_at = CURRENT_TIMESTAMP;
DROP TEMPORARY TABLE normalized_agent_model_capabilities;

DROP TEMPORARY TABLE IF EXISTS normalized_provider_capabilities;
CREATE TEMPORARY TABLE normalized_provider_capabilities (
  id BIGINT PRIMARY KEY,
  capabilities JSON NOT NULL
);
INSERT INTO normalized_provider_capabilities (id, capabilities)
SELECT id, JSON_ARRAY()
FROM model_provider_metadata
WHERE JSON_VALID(capabilities_json) = 1
  AND JSON_TYPE(IF(JSON_VALID(capabilities_json), capabilities_json, JSON_ARRAY())) = 'ARRAY';
UPDATE normalized_provider_capabilities normalized
JOIN (
  SELECT id, JSON_ARRAYAGG(capability) AS capabilities
  FROM (
    SELECT DISTINCT source.id,
           UPPER(TRIM(capability_row.raw_capability)) AS capability
    FROM model_provider_metadata source
    JOIN JSON_TABLE(
      IF(
        JSON_TYPE(IF(JSON_VALID(source.capabilities_json), source.capabilities_json, JSON_ARRAY())) = 'ARRAY',
        source.capabilities_json,
        JSON_ARRAY()
      ),
      '$[*]' COLUMNS (
        raw_capability VARCHAR(128) PATH '$' NULL ON EMPTY NULL ON ERROR
      )
    ) capability_row ON TRUE
    WHERE JSON_VALID(source.capabilities_json) = 1
      AND JSON_TYPE(IF(JSON_VALID(source.capabilities_json), source.capabilities_json, JSON_ARRAY())) = 'ARRAY'
      AND capability_row.raw_capability IS NOT NULL
      AND TRIM(capability_row.raw_capability) <> ''
      AND UPPER(TRIM(capability_row.raw_capability)) <> 'DIGITAL_HUMAN'
  ) distinct_capabilities
  GROUP BY id
) rebuilt ON rebuilt.id = normalized.id
SET normalized.capabilities = rebuilt.capabilities;
UPDATE model_provider_metadata target
JOIN normalized_provider_capabilities normalized ON normalized.id = target.id
SET target.capabilities_json = normalized.capabilities,
    target.updated_at = CURRENT_TIMESTAMP;
DROP TEMPORARY TABLE normalized_provider_capabilities;

-- Convert any pre-release tool values before applying the null-column backfill.
DROP TEMPORARY TABLE IF EXISTS normalized_tool_capabilities;
CREATE TEMPORARY TABLE normalized_tool_capabilities (
  id BIGINT PRIMARY KEY,
  capabilities JSON NOT NULL
);
INSERT INTO normalized_tool_capabilities (id, capabilities)
SELECT id, JSON_ARRAY()
FROM ai_tools
WHERE JSON_VALID(required_model_capabilities) = 1
  AND JSON_TYPE(
    IF(JSON_VALID(required_model_capabilities), required_model_capabilities, JSON_ARRAY())
  ) = 'ARRAY';
UPDATE normalized_tool_capabilities normalized
JOIN (
  SELECT id, JSON_ARRAYAGG(capability) AS capabilities
  FROM (
    SELECT DISTINCT source.id,
           CASE
             WHEN UPPER(TRIM(capability_row.raw_capability)) = 'DIGITAL_HUMAN'
               THEN 'VIDEO_GENERATION'
             ELSE UPPER(TRIM(capability_row.raw_capability))
           END AS capability
    FROM ai_tools source
    JOIN JSON_TABLE(
      IF(
        JSON_TYPE(
          IF(
            JSON_VALID(source.required_model_capabilities),
            source.required_model_capabilities,
            JSON_ARRAY()
          )
        ) = 'ARRAY',
        source.required_model_capabilities,
        JSON_ARRAY()
      ),
      '$[*]' COLUMNS (
        raw_capability VARCHAR(128) PATH '$' NULL ON EMPTY NULL ON ERROR
      )
    ) capability_row ON TRUE
    WHERE JSON_VALID(source.required_model_capabilities) = 1
      AND JSON_TYPE(
        IF(
          JSON_VALID(source.required_model_capabilities),
          source.required_model_capabilities,
          JSON_ARRAY()
        )
      ) = 'ARRAY'
      AND capability_row.raw_capability IS NOT NULL
      AND TRIM(capability_row.raw_capability) <> ''
  ) distinct_capabilities
  GROUP BY id
) rebuilt ON rebuilt.id = normalized.id
SET normalized.capabilities = rebuilt.capabilities;
UPDATE ai_tools target
JOIN normalized_tool_capabilities normalized ON normalized.id = target.id
SET target.required_model_capabilities = normalized.capabilities,
    target.updated_at = CURRENT_TIMESTAMP;
DROP TEMPORARY TABLE normalized_tool_capabilities;

-- Backfill the new tool-level model requirement from the legacy execution contract.
UPDATE ai_tools
SET required_model_capabilities = CASE UPPER(COALESCE(
      NULLIF(TRIM(execution_handler), ''),
      NULLIF(TRIM(tool_type), ''),
      'TEXT_GENERATION'
    ))
      WHEN 'DIGITAL_HUMAN' THEN '["VIDEO_GENERATION"]'
      WHEN 'IMAGE_TO_IMAGE' THEN '["IMAGE_GENERATION"]'
      WHEN 'IMAGE_UNDERSTANDING' THEN '["TEXT_GENERATION","VISION_INPUT"]'
      WHEN 'AGENT' THEN '["TEXT_GENERATION"]'
      WHEN 'IMAGE_GENERATION' THEN '["IMAGE_GENERATION"]'
      WHEN 'VIDEO_GENERATION' THEN '["VIDEO_GENERATION"]'
      WHEN 'MUSIC_GENERATION' THEN '["MUSIC_GENERATION"]'
      WHEN 'TEXT_TO_SPEECH' THEN '["TEXT_TO_SPEECH"]'
      WHEN 'SPEECH_TO_TEXT' THEN '["SPEECH_TO_TEXT"]'
      WHEN 'EMBEDDING' THEN '["EMBEDDING"]'
      WHEN 'RERANK' THEN '["RERANK"]'
      ELSE '["TEXT_GENERATION"]'
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE required_model_capabilities IS NULL
   OR TRIM(required_model_capabilities) = ''
   OR JSON_VALID(required_model_capabilities) = 0
   OR JSON_TYPE(
        IF(JSON_VALID(required_model_capabilities), required_model_capabilities, JSON_ARRAY())
      ) <> 'ARRAY'
   OR JSON_LENGTH(
        IF(JSON_VALID(required_model_capabilities), required_model_capabilities, JSON_ARRAY())
      ) = 0;

-- Digital-human execution only has Seedance and InfiniteTalk video implementations.
-- Clear incompatible bindings so the normal model matcher can select a supported video model.
UPDATE ai_tools
SET model_config_id = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE UPPER(COALESCE(execution_handler, '')) = 'DIGITAL_HUMAN'
  AND model_config_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM agent_model_configs model
    LEFT JOIN model_provider_metadata metadata
      ON metadata.provider_code = model.provider
     AND metadata.enabled = 1
    WHERE model.id = ai_tools.model_config_id
      AND model.is_deleted = 0
      AND LOWER(COALESCE(model.provider, '')) IN ('seedance', 'infinitetalk')
      AND JSON_CONTAINS(
        CASE
          WHEN JSON_TYPE(IF(JSON_VALID(model.capabilities), model.capabilities, JSON_ARRAY())) = 'ARRAY'
           AND JSON_LENGTH(IF(JSON_VALID(model.capabilities), model.capabilities, JSON_ARRAY())) > 0
            THEN model.capabilities
          WHEN JSON_TYPE(IF(JSON_VALID(metadata.capabilities_json), metadata.capabilities_json, JSON_ARRAY())) = 'ARRAY'
            THEN metadata.capabilities_json
          ELSE JSON_ARRAY()
        END,
        JSON_QUOTE('VIDEO_GENERATION')
      ) = 1
  );
