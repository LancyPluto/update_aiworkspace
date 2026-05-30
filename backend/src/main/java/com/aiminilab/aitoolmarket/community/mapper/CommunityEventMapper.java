package com.aiminilab.aitoolmarket.community.mapper;

import com.aiminilab.aitoolmarket.community.dto.CommunityStatsResponse;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityEventMapper {

    @Insert("""
            INSERT INTO community_events (post_id, user_id, event_type, source, tool_code, task_id, credits)
            VALUES (#{postId}, #{userId}, #{eventType}, #{source}, #{toolCode}, #{taskId}, #{credits})
            """)
    int insertEvent(@Param("postId") Long postId,
                    @Param("userId") Long userId,
                    @Param("eventType") String eventType,
                    @Param("source") String source,
                    @Param("toolCode") String toolCode,
                    @Param("taskId") Long taskId,
                    @Param("credits") Integer credits);

    @Select("""
            SELECT COUNT(*)
            FROM community_events
            WHERE event_type = #{eventType}
            """)
    long countByType(@Param("eventType") String eventType);

    @Select("""
            SELECT COALESCE(SUM(credits), 0)
            FROM community_events
            WHERE event_type = 'credit_spent'
            """)
    long sumCredits();

    @Select("""
            SELECT COALESCE(tool_code, 'unknown') AS name, COUNT(*) AS value
            FROM community_events
            WHERE tool_code IS NOT NULL AND tool_code != ''
            GROUP BY tool_code
            ORDER BY value DESC
            LIMIT #{limit}
            """)
    List<CommunityStatsResponse.MetricPoint> topTools(@Param("limit") int limit);

    @Select("""
            SELECT COALESCE(p.topic, 'unassigned') AS name, COUNT(*) AS value
            FROM community_events e
            JOIN community_posts p ON p.id = e.post_id
            GROUP BY p.topic
            ORDER BY value DESC
            LIMIT #{limit}
            """)
    List<CommunityStatsResponse.MetricPoint> topTopics(@Param("limit") int limit);

    @Select("""
            SELECT CAST(p.user_id AS CHAR) AS name, COUNT(*) AS value
            FROM community_events e
            JOIN community_posts p ON p.id = e.post_id
            GROUP BY p.user_id
            ORDER BY value DESC
            LIMIT #{limit}
            """)
    List<CommunityStatsResponse.MetricPoint> topCreators(@Param("limit") int limit);
}
