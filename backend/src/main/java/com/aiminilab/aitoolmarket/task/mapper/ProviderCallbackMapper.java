package com.aiminilab.aitoolmarket.task.mapper;

import com.aiminilab.aitoolmarket.task.entity.ProviderCallbackInboxEvent;
import com.aiminilab.aitoolmarket.task.entity.ProviderCallbackRegistration;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ProviderCallbackMapper {

    @Insert("""
            INSERT INTO provider_callback_registrations(
                provider_code, task_id, route_attempt_id, token_hash, status, expires_at,
                created_at, updated_at
            ) VALUES(
                #{registration.providerCode}, #{registration.taskId}, #{registration.routeAttemptId},
                #{registration.tokenHash}, 'PENDING', #{registration.expiresAt},
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "registration.id")
    int insertRegistration(@Param("registration") ProviderCallbackRegistration registration);

    @Select("""
            SELECT *
            FROM provider_callback_registrations
            WHERE token_hash = #{tokenHash}
              AND status IN ('PENDING', 'BOUND', 'COMPLETED')
              AND expires_at >= CURRENT_TIMESTAMP
            LIMIT 1
            """)
    ProviderCallbackRegistration findActiveRegistration(@Param("tokenHash") String tokenHash);

    @Update("""
            UPDATE provider_callback_registrations
            SET provider_task_id = COALESCE(provider_task_id, #{providerTaskId}),
                status = CASE WHEN status = 'PENDING' THEN 'BOUND' ELSE status END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{registrationId}
              AND (provider_task_id IS NULL OR provider_task_id = #{providerTaskId})
            """)
    int bindProviderTask(@Param("registrationId") Long registrationId,
                         @Param("providerTaskId") String providerTaskId);

    @Insert("""
            INSERT INTO provider_callback_inbox(
                registration_id, provider_code, task_id, provider_task_id, callback_type,
                provider_status_code, payload_json, payload_sha256, process_status, received_at
            ) VALUES(
                #{event.registrationId}, #{event.providerCode}, #{event.taskId},
                #{event.providerTaskId}, #{event.callbackType}, #{event.providerStatusCode},
                #{event.payloadJson}, #{event.payloadSha256}, 'PENDING', CURRENT_TIMESTAMP
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertInbox(@Param("event") ProviderCallbackInboxEvent event);

    @Select("""
            SELECT *
            FROM provider_callback_inbox
            WHERE task_id = #{taskId}
              AND provider_code = #{providerCode}
              AND (callback_type IN ('complete', 'error') OR provider_status_code <> 200)
            ORDER BY id DESC
            LIMIT 1
            """)
    ProviderCallbackInboxEvent findLatestTerminal(@Param("taskId") Long taskId,
                                                   @Param("providerCode") String providerCode);

    @Update("""
            UPDATE provider_callback_registrations
            SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{registrationId}
            """)
    int markRegistrationCompleted(@Param("registrationId") Long registrationId);
}
