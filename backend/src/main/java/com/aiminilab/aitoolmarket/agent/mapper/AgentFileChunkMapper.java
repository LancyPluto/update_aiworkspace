package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentFileChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentFileChunkMapper extends BaseMapper<AgentFileChunk> {

    @Insert("""
            INSERT INTO agent_file_chunks(file_id, session_id, user_id, chunk_index, content_text, metadata_json, created_at)
            VALUES(#{chunk.fileId}, #{chunk.sessionId}, #{chunk.userId}, #{chunk.chunkIndex}, #{chunk.contentText},
                   #{chunk.metadataJson}, #{chunk.createdAt})
            """)
    void insertChunk(@Param("chunk") AgentFileChunk chunk);

    @Delete("""
            DELETE FROM agent_file_chunks
            WHERE file_id = #{fileId}
            """)
    void deleteByFileId(@Param("fileId") Long fileId);

    @Select("""
            SELECT c.*
            FROM agent_file_chunks c
            JOIN agent_files f ON f.id = c.file_id
            WHERE c.session_id = #{sessionId}
              AND c.user_id = #{userId}
              AND f.status = 'READY'
            ORDER BY c.file_id DESC, c.chunk_index ASC
            LIMIT #{limit}
            """)
    List<AgentFileChunk> findReadyBySession(@Param("userId") Long userId,
                                            @Param("sessionId") Long sessionId,
                                            @Param("limit") int limit);
}
