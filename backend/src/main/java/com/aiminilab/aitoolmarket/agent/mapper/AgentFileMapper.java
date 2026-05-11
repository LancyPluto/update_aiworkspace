package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentFileMapper extends BaseMapper<AgentFile> {

    @Insert("""
            INSERT INTO agent_files(session_id, user_id, original_filename, content_type, file_size, storage_path,
                                    status, extracted_text, error_message, created_at, updated_at)
            VALUES(#{file.sessionId}, #{file.userId}, #{file.originalFilename}, #{file.contentType}, #{file.fileSize},
                   #{file.storagePath}, #{file.status}, #{file.extractedText}, #{file.errorMessage},
                   #{file.createdAt}, #{file.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "file.id")
    void insertFile(@Param("file") AgentFile file);

    @Select("""
            SELECT *
            FROM agent_files
            WHERE session_id = #{sessionId} AND user_id = #{userId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentFile> findBySession(@Param("userId") Long userId,
                                  @Param("sessionId") Long sessionId,
                                  @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_files
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND status = 'READY'
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentFile> findReadyBySession(@Param("userId") Long userId,
                                       @Param("sessionId") Long sessionId,
                                       @Param("limit") int limit);

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
}
