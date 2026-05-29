DELIMITER //

CREATE PROCEDURE add_index_if_missing(
    IN table_name_in VARCHAR(64),
    IN index_name_in VARCHAR(64),
    IN ddl_in TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = table_name_in
          AND index_name = index_name_in
    ) THEN
        SET @ddl = ddl_in;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

DELIMITER ;

CALL add_index_if_missing('agent_messages', 'idx_agent_messages_session_active', 'CREATE INDEX idx_agent_messages_session_active ON agent_messages(session_id, status, id)');
CALL add_index_if_missing('agent_runs', 'idx_agent_runs_session_user_id', 'CREATE INDEX idx_agent_runs_session_user_id ON agent_runs(session_id, user_id, id)');
CALL add_index_if_missing('agent_tool_calls', 'idx_agent_tool_calls_context_recent', 'CREATE INDEX idx_agent_tool_calls_context_recent ON agent_tool_calls(user_id, status, id)');

DROP PROCEDURE IF EXISTS add_index_if_missing;
