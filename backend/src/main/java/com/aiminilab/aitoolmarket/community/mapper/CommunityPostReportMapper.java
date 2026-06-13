package com.aiminilab.aitoolmarket.community.mapper;

import com.aiminilab.aitoolmarket.community.entity.CommunityPostReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CommunityPostReportMapper {

    @Insert("""
            INSERT INTO community_post_reports (post_id, reporter_user_id, reason, status)
            VALUES (#{postId}, #{reporterUserId}, #{reason}, 'PENDING')
            """)
    int insertReport(@Param("postId") Long postId,
                     @Param("reporterUserId") Long reporterUserId,
                     @Param("reason") String reason);

    @Select("""
            SELECT COUNT(*)
            FROM community_post_reports
            WHERE post_id = #{postId}
              AND reporter_user_id = #{reporterUserId}
            """)
    long countByPostAndReporter(@Param("postId") Long postId, @Param("reporterUserId") Long reporterUserId);

    @Select("""
            SELECT COUNT(*)
            FROM community_post_reports
            WHERE status = #{status}
            """)
    long countByStatus(@Param("status") String status);

    @Select("""
            SELECT r.id,
                   r.post_id,
                   r.reporter_user_id,
                   r.reason,
                   r.status,
                   r.admin_note,
                   r.reviewed_at,
                   r.created_at,
                   r.updated_at,
                   p.title AS post_title,
                   COALESCE(p.cover_url, p.media_url) AS post_cover_url,
                   p.status AS post_status
            FROM community_post_reports r
            JOIN community_posts p ON p.id = r.post_id
            WHERE (#{status} IS NULL OR #{status} = '' OR r.status = #{status})
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<CommunityPostReport> findForAdmin(@Param("status") String status,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM community_post_reports r
            WHERE (#{status} IS NULL OR #{status} = '' OR r.status = #{status})
            """)
    long countForAdmin(@Param("status") String status);

    @Update("""
            UPDATE community_post_reports
            SET status = #{status},
                admin_note = #{adminNote},
                reviewed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{reportId}
            """)
    int updateStatus(@Param("reportId") Long reportId,
                     @Param("status") String status,
                     @Param("adminNote") String adminNote);

    @Select("""
            SELECT r.id,
                   r.post_id,
                   r.reporter_user_id,
                   r.reason,
                   r.status,
                   r.admin_note,
                   r.reviewed_at,
                   r.created_at,
                   r.updated_at,
                   p.title AS post_title,
                   COALESCE(p.cover_url, p.media_url) AS post_cover_url,
                   p.status AS post_status
            FROM community_post_reports r
            JOIN community_posts p ON p.id = r.post_id
            WHERE r.id = #{reportId}
            """)
    CommunityPostReport findById(@Param("reportId") Long reportId);
}
