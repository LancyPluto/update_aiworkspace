package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentRunEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentRunEventMapper extends BaseMapper<AgentRunEvent> {

    @Insert("""
            INSERT INTO agent_run_events(run_id, user_id, event_type, event_text, event_json, created_at)
            VALUES(#{event.runId}, #{event.userId}, #{event.eventType}, #{event.eventText}, #{event.eventJson}, #{event.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    void insertEvent(@Param("event") AgentRunEvent event);

    @Select("""
            <script>
            SELECT e.*
            FROM agent_run_events e
            JOIN agent_runs r ON r.id = e.run_id
            WHERE e.run_id = #{runId} AND r.user_id = #{userId}
            <if test="afterEventId != null">
              AND e.id &gt; #{afterEventId}
            </if>
            ORDER BY e.id ASC
            LIMIT #{limit}
            </script>
            """)
    List<AgentRunEvent> findEvents(@Param("userId") Long userId,
                                   @Param("runId") Long runId,
                                   @Param("afterEventId") Long afterEventId,
                                   @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_run_events
            WHERE run_id = #{runId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<AgentRunEvent> findEventsForAdmin(@Param("runId") Long runId, @Param("limit") int limit);
}
