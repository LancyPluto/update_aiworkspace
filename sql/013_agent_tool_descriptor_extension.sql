-- Agent tool descriptor extension table.
-- Stores admin-controlled Agent visibility and runtime health for marketplace tools.
CREATE TABLE IF NOT EXISTS `agent_tool_descriptor_extension` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `tool_id`               BIGINT          NOT NULL COMMENT 'Related ai_tools.id',
    `tool_code`             VARCHAR(64)     NOT NULL COMMENT 'Unique tool code',
    `agent_enabled`         TINYINT(1)      NOT NULL DEFAULT 1 COMMENT 'Whether Agent can see this tool',
    `agent_recommendable`   TINYINT(1)      NOT NULL DEFAULT 1 COMMENT 'Whether Agent can recommend this tool',
    `agent_auto_callable`   TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'Whether Agent can call this tool without confirmation',
    `confirmation_policy`   VARCHAR(32)     DEFAULT 'auto' COMMENT 'auto / always_confirm / skip',
    `risk_level`            VARCHAR(16)     DEFAULT 'low' COMMENT 'low / medium / high',
    `keywords_json`         TEXT            DEFAULT NULL COMMENT 'Recommendation keywords JSON array',
    `example_prompts_json`  TEXT            DEFAULT NULL COMMENT 'Example prompts JSON array',
    `applicable_scenarios_json` TEXT        DEFAULT NULL COMMENT 'Applicable scenarios JSON array',
    `not_applicable_scenarios_json` TEXT    DEFAULT NULL COMMENT 'Not applicable scenarios JSON array',
    `result_schema_json`    TEXT            DEFAULT NULL COMMENT 'Result schema description JSON',
    `output_type`           VARCHAR(32)     DEFAULT 'text' COMMENT 'text / json / image / file',
    `health_status`         VARCHAR(32)     NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / HEALTHY / FAILED',
    `health_message`        VARCHAR(512)    DEFAULT NULL COMMENT 'Latest actionable health failure reason',
    `health_checked_at`     DATETIME        DEFAULT NULL COMMENT 'Latest health update time',
    `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_code` (`tool_code`),
    KEY `idx_enabled_recommendable` (`agent_enabled`, `agent_recommendable`),
    KEY `idx_agent_tool_health` (`agent_enabled`, `health_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent tool descriptor extension metadata';
