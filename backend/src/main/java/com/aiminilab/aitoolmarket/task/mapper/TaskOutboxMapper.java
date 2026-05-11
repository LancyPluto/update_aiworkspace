package com.aiminilab.aitoolmarket.task.mapper;

import com.aiminilab.aitoolmarket.task.entity.TaskOutboxEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskOutboxMapper extends BaseMapper<TaskOutboxEvent> {

    @Select("""
            SELECT *
            FROM task_outbox_events
            WHERE status = 'PENDING'
              AND next_retry_at <= CURRENT_TIMESTAMP
            ORDER BY next_retry_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<TaskOutboxEvent> findPending(@Param("limit") int limit);

    @Update("""
            UPDATE task_outbox_events
            SET status = 'SENT', last_error = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{eventId} AND status = 'PENDING'
            """)
    int markSent(@Param("eventId") Long eventId);

    @Update("""
            UPDATE task_outbox_events
            SET retry_count = retry_count + 1,
                next_retry_at = #{nextRetryAt},
                last_error = #{lastError},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{eventId} AND status = 'PENDING'
            """)
    int markFailed(@Param("eventId") Long eventId,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("lastError") String lastError);
}
