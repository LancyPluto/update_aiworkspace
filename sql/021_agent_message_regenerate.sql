-- Agent 消息重新生成（方案 A）：消息状态 + run 溯源与幂等键
-- 与 DataInitializer.ensureColumn 配合，存量库启动时自动补列

ALTER TABLE agent_messages
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|SUPERSEDED',
  ADD COLUMN superseded_at DATETIME NULL COMMENT '作废时间';

CREATE INDEX idx_agent_messages_session_active
  ON agent_messages (session_id, status, id);

ALTER TABLE agent_runs
  ADD COLUMN parent_run_id BIGINT NULL COMMENT '重新生成来源 run',
  ADD COLUMN source_user_message_id BIGINT NULL COMMENT '本轮对应的 USER message id',
  ADD COLUMN client_request_id VARCHAR(64) NULL COMMENT '客户端幂等键';

CREATE UNIQUE INDEX uk_agent_runs_user_client
  ON agent_runs (user_id, client_request_id);
