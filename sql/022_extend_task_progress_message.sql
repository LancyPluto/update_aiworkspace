-- 支持任务执行过程中的流式正文预览（progress_message 原 VARCHAR(255) 过短）
ALTER TABLE ai_tasks
    MODIFY COLUMN progress_message TEXT NULL COMMENT '进度说明，可含 STREAM_PREVIEW: 前缀的流式预览正文';
