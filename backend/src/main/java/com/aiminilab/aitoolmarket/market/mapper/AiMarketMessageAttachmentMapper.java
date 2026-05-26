package com.aiminilab.aitoolmarket.market.mapper;

import com.aiminilab.aitoolmarket.market.entity.AiMarketMessageAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiMarketMessageAttachmentMapper extends BaseMapper<AiMarketMessageAttachment> {

    @Select("""
            SELECT *
            FROM ai_market_message_attachments
            WHERE message_id = #{messageId}
            """)
    List<AiMarketMessageAttachment> findByMessageId(@Param("messageId") String messageId);

    @Insert("""
            INSERT INTO ai_market_message_attachments (
              message_id, file_id, file_name, file_size, content_type
            ) VALUES (
              #{attachment.messageId}, #{attachment.fileId}, #{attachment.fileName},
              #{attachment.fileSize}, #{attachment.contentType}
            )
            """)
    int insertAttachment(@Param("attachment") AiMarketMessageAttachment attachment);

    @Delete("DELETE FROM ai_market_message_attachments WHERE message_id = #{messageId}")
    int deleteByMessageId(@Param("messageId") String messageId);
}
