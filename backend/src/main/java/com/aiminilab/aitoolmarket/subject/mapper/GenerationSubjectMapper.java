package com.aiminilab.aitoolmarket.subject.mapper;

import com.aiminilab.aitoolmarket.subject.entity.GenerationSubject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface GenerationSubjectMapper extends BaseMapper<GenerationSubject> {

    @Insert("""
            INSERT INTO user_generation_subjects(user_id, subject_code, display_name, description, provider_code,
                                                 vendor_account_ref, reference_type, preview_url, reference_json,
                                                 upstream_element_id, sync_task_id, sync_status, sync_error,
                                                 status, created_at, updated_at)
            VALUES(#{subject.userId}, #{subject.subjectCode}, #{subject.displayName}, #{subject.description},
                   #{subject.providerCode}, #{subject.vendorAccountRef}, #{subject.referenceType},
                   #{subject.previewUrl}, #{subject.referenceJson}, #{subject.upstreamElementId},
                   #{subject.syncTaskId}, #{subject.syncStatus}, #{subject.syncError}, #{subject.status},
                   #{subject.createdAt}, #{subject.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "subject.id")
    void insertSubject(@Param("subject") GenerationSubject subject);

    @Select("""
            SELECT *
            FROM user_generation_subjects
            WHERE subject_code = #{subjectCode}
              AND status = 'ACTIVE'
            LIMIT 1
            """)
    GenerationSubject findActiveByCodeOnly(@Param("subjectCode") String subjectCode);

    @Select("""
            SELECT *
            FROM user_generation_subjects
            WHERE user_id = #{userId}
              AND subject_code = #{subjectCode}
              AND status = 'ACTIVE'
            LIMIT 1
            """)
    GenerationSubject findActiveByCode(@Param("userId") Long userId, @Param("subjectCode") String subjectCode);

    @Select("""
            SELECT *
            FROM user_generation_subjects
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND (#{providerCode} IS NULL OR #{providerCode} = '' OR provider_code = #{providerCode})
              AND (#{syncStatus} IS NULL OR #{syncStatus} = '' OR sync_status = #{syncStatus})
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<GenerationSubject> findByUser(@Param("userId") Long userId,
                                       @Param("providerCode") String providerCode,
                                       @Param("syncStatus") String syncStatus,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    @Select("""
            SELECT COUNT(1)
            FROM user_generation_subjects
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND (#{providerCode} IS NULL OR #{providerCode} = '' OR provider_code = #{providerCode})
              AND (#{syncStatus} IS NULL OR #{syncStatus} = '' OR sync_status = #{syncStatus})
            """)
    long countByUser(@Param("userId") Long userId,
                     @Param("providerCode") String providerCode,
                     @Param("syncStatus") String syncStatus);

    @Update("""
            UPDATE user_generation_subjects
            SET sync_status = #{syncStatus},
                sync_task_id = #{syncTaskId},
                upstream_element_id = #{upstreamElementId},
                sync_error = #{syncError},
                updated_at = #{updatedAt}
            WHERE user_id = #{userId}
              AND subject_code = #{subjectCode}
              AND status = 'ACTIVE'
            """)
    int updateSyncState(@Param("userId") Long userId,
                        @Param("subjectCode") String subjectCode,
                        @Param("syncStatus") String syncStatus,
                        @Param("syncTaskId") String syncTaskId,
                        @Param("upstreamElementId") String upstreamElementId,
                        @Param("syncError") String syncError,
                        @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE user_generation_subjects
            SET status = 'DELETED', updated_at = #{updatedAt}
            WHERE user_id = #{userId}
              AND subject_code = #{subjectCode}
              AND status = 'ACTIVE'
            """)
    int softDelete(@Param("userId") Long userId,
                   @Param("subjectCode") String subjectCode,
                   @Param("updatedAt") LocalDateTime updatedAt);
}
