package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentFileMapper extends BaseMapper<AgentFile> {

    @Insert("""
            INSERT INTO agent_files(session_id, user_id, original_filename, content_type, file_size, storage_path,
                                    status, attached_run_id, extracted_text, error_message, created_at, updated_at)
            VALUES(#{file.sessionId}, #{file.userId}, #{file.originalFilename}, #{file.contentType}, #{file.fileSize},
                   #{file.storagePath}, #{file.status}, #{file.attachedRunId}, #{file.extractedText}, #{file.errorMessage},
                   #{file.createdAt}, #{file.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "file.id")
    void insertFile(@Param("file") AgentFile file);

    @Select("""
            SELECT *
            FROM agent_files
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND attached_run_id IS NULL
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentFile> findPendingBySession(@Param("userId") Long userId,
                                         @Param("sessionId") Long sessionId,
                                         @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_files
            WHERE user_id = #{userId}
              AND status = 'READY'
              AND (content_type LIKE 'image/%' OR content_type LIKE 'video/%' OR content_type LIKE 'audio/%')
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentFile> findRecentMediaByUser(@Param("userId") Long userId,
                                          @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_files
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND status = 'READY' AND attached_run_id = #{runId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentFile> findReadyByRun(@Param("userId") Long userId,
                                   @Param("sessionId") Long sessionId,
                                   @Param("runId") Long runId,
                                   @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_files
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND attached_run_id = #{runId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentFile> findByRun(@Param("userId") Long userId,
                              @Param("sessionId") Long sessionId,
                              @Param("runId") Long runId,
                              @Param("limit") int limit);

    @Update("""
            UPDATE agent_files
            SET attached_run_id = #{runId}, updated_at = #{updatedAt}
            WHERE session_id = #{sessionId}
              AND user_id = #{userId}
              AND attached_run_id IS NULL
            """)
    int attachAllPendingFilesToRun(@Param("userId") Long userId,
                                   @Param("sessionId") Long sessionId,
                                   @Param("runId") Long runId,
                                   @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE agent_files
            SET attached_run_id = #{runId}, updated_at = #{updatedAt}
            WHERE id = #{fileId}
              AND session_id = #{sessionId}
              AND user_id = #{userId}
              AND attached_run_id IS NULL
            """)
    int attachPendingFileToRun(@Param("userId") Long userId,
                               @Param("sessionId") Long sessionId,
                               @Param("runId") Long runId,
                               @Param("fileId") Long fileId,
                               @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE agent_files
            SET attached_run_id = #{targetRunId}, updated_at = #{updatedAt}
            WHERE session_id = #{sessionId}
              AND user_id = #{userId}
              AND attached_run_id = #{sourceRunId}
            """)
    int reattachFilesFromRun(@Param("userId") Long userId,
                              @Param("sessionId") Long sessionId,
                              @Param("sourceRunId") Long sourceRunId,
                              @Param("targetRunId") Long targetRunId,
                              @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE agent_files
            SET status = #{status}, extracted_text = #{extractedText}, error_message = #{errorMessage}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateParseResult(@Param("id") Long id,
                           @Param("status") String status,
                           @Param("extractedText") String extractedText,
                           @Param("errorMessage") String errorMessage,
                           @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT *
            FROM agent_files
            WHERE id = #{fileId} AND session_id = #{sessionId} AND user_id = #{userId}
            LIMIT 1
            """)
    AgentFile selectByIdSessionAndUser(@Param("fileId") Long fileId,
                                       @Param("sessionId") Long sessionId,
                                       @Param("userId") Long userId);

    default Optional<AgentFile> findByIdSessionAndUser(Long fileId, Long sessionId, Long userId) {
        return Optional.ofNullable(selectByIdSessionAndUser(fileId, sessionId, userId));
    }

    @Select("""
            SELECT COUNT(1)
            FROM agent_files
            WHERE id = #{fileId}
              AND user_id = #{userId}
              AND status = 'READY'
            """)
    long countReadyByIdAndUser(@Param("fileId") Long fileId,
                               @Param("userId") Long userId);

    @Delete("""
            DELETE FROM agent_files
            WHERE id = #{fileId} AND session_id = #{sessionId} AND user_id = #{userId}
            """)
    int deleteByIdSessionAndUser(@Param("fileId") Long fileId,
                                 @Param("sessionId") Long sessionId,
                                 @Param("userId") Long userId);
}
