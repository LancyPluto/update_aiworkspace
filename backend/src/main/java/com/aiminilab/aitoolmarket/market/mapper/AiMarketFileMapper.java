package com.aiminilab.aitoolmarket.market.mapper;

import com.aiminilab.aitoolmarket.market.entity.AiMarketFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

public interface AiMarketFileMapper extends BaseMapper<AiMarketFile> {

    @Select("""
            SELECT *
            FROM ai_market_files
            WHERE file_id = #{fileId} AND user_id = #{userId}
            LIMIT 1
            """)
    AiMarketFile findByFileIdAndUser(@Param("fileId") String fileId, @Param("userId") Long userId);

    default Optional<AiMarketFile> findOptional(String fileId, Long userId) {
        return Optional.ofNullable(findByFileIdAndUser(fileId, userId));
    }

    @Select("""
            <script>
            SELECT *
            FROM ai_market_files
            WHERE user_id = #{userId}
              AND file_id IN
              <foreach collection="fileIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    List<AiMarketFile> findByFileIdsAndUser(@Param("userId") Long userId, @Param("fileIds") List<String> fileIds);

    @Insert("""
            INSERT INTO ai_market_files (
              file_id, user_id, tool_id, original_name, storage_path, content_type, file_size, created_at
            ) VALUES (
              #{file.fileId}, #{file.userId}, #{file.toolId}, #{file.originalName},
              #{file.storagePath}, #{file.contentType}, #{file.fileSize}, #{file.createdAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "file.id")
    int insertFile(@Param("file") AiMarketFile file);
}
