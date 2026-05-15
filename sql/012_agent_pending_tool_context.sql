-- Agent Pending Tool Context table
-- 用于持久化多轮补参上下文，解决靠正则判断的不稳定问题
CREATE TABLE IF NOT EXISTS `agent_pending_tool_context` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `run_id`                BIGINT          NOT NULL                 COMMENT '关联 run id',
    `session_id`            BIGINT          NOT NULL                 COMMENT '关联会话 id',
    `user_id`               BIGINT          NOT NULL                 COMMENT '用户 id',
    `selected_tool_code`    VARCHAR(64)     DEFAULT NULL             COMMENT '当前选中的工具 code',
    `candidate_tool_codes_json` TEXT        DEFAULT NULL             COMMENT '候选工具 code 列表 JSON',
    `collected_arguments_json` TEXT         DEFAULT NULL             COMMENT '已收集的参数 JSON',
    `missing_arguments_json` TEXT           DEFAULT NULL             COMMENT '缺失参数列表 JSON',
    `clarifying_question`   VARCHAR(2000)   DEFAULT NULL             COMMENT '上一轮追问内容',
    `confirmation_required` TINYINT(1)      DEFAULT 0                COMMENT '是否需要用户确认',
    `source`                VARCHAR(32)     DEFAULT 'intent_router'  COMMENT '来源：intent_router / tool_preference / user_input',
    `status`                VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / CONFIRMED / CANCELLED / RESOLVED / EXPIRED',
    `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_run_id_status` (`run_id`, `status`),
    KEY `idx_user_id_status` (`user_id`, `status`),
    KEY `idx_session_id_updated` (`session_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 待补参上下文';
