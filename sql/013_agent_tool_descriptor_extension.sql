-- Agent Tool Descriptor Extension table
-- 让平台工具通过元数据接入 Agent 能力，替代代码白名单
CREATE TABLE IF NOT EXISTS `agent_tool_descriptor_extension` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `tool_id`               BIGINT          NOT NULL                 COMMENT '关联工具 id',
    `tool_code`             VARCHAR(64)     NOT NULL                 COMMENT '工具 code（唯一）',
    `agent_enabled`         TINYINT(1)      NOT NULL DEFAULT 1       COMMENT '是否启用 Agent 能力',
    `agent_recommendable`   TINYINT(1)      NOT NULL DEFAULT 1       COMMENT '是否可被 Agent 推荐',
    `agent_auto_callable`   TINYINT(1)      NOT NULL DEFAULT 0       COMMENT '是否可自动调用（跳过用户确认）',
    `confirmation_policy`   VARCHAR(32)     DEFAULT 'auto'           COMMENT '确认策略：auto / always_confirm / skip',
    `risk_level`            VARCHAR(16)     DEFAULT 'low'            COMMENT '风险等级：low / medium / high',
    `keywords_json`         TEXT            DEFAULT NULL             COMMENT '推荐关键词 JSON 数组',
    `example_prompts_json`  TEXT            DEFAULT NULL             COMMENT '示例提示词 JSON 数组',
    `applicable_scenarios_json` TEXT        DEFAULT NULL             COMMENT '适用场景 JSON 数组',
    `not_applicable_scenarios_json` TEXT    DEFAULT NULL             COMMENT '不适用场景 JSON 数组',
    `result_schema_json`    TEXT            DEFAULT NULL             COMMENT '结果结构描述 JSON',
    `output_type`           VARCHAR(32)     DEFAULT 'text'           COMMENT '输出类型：text / json / image / file',
    `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_code` (`tool_code`),
    KEY `idx_enabled_recommendable` (`agent_enabled`, `agent_recommendable`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工具描述扩展元数据';
