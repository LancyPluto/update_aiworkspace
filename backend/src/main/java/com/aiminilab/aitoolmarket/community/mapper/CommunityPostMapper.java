package com.aiminilab.aitoolmarket.community.mapper;

import com.aiminilab.aitoolmarket.community.dto.CommunityPostDiscoverRow;
import com.aiminilab.aitoolmarket.community.dto.CommunityTopicResponse;
import com.aiminilab.aitoolmarket.community.entity.CommunityPost;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CommunityPostMapper extends BaseMapper<CommunityPost> {

    default Long insertAndReturnId(CommunityPost post) {
        insert(post);
        return post.getId();
    }

    @Select("""
            SELECT *
            FROM community_posts
            WHERE task_id = #{taskId}
            LIMIT 1
            """)
    CommunityPost selectByTaskId(@Param("taskId") Long taskId);

    default Optional<CommunityPost> findByTaskId(Long taskId) {
        return Optional.ofNullable(selectByTaskId(taskId));
    }

    @Select("""
            SELECT *
            FROM community_posts
            WHERE task_id = #{taskId}
            LIMIT 1
            FOR UPDATE
            """)
    CommunityPost selectByTaskIdForUpdate(@Param("taskId") Long taskId);

    default Optional<CommunityPost> findByTaskIdForUpdate(Long taskId) {
        return Optional.ofNullable(selectByTaskIdForUpdate(taskId));
    }

    @Select("""
            SELECT *
            FROM community_posts
            WHERE id = #{postId}
            LIMIT 1
            """)
    CommunityPost selectPostById(@Param("postId") Long postId);

    default Optional<CommunityPost> findPostById(Long postId) {
        return Optional.ofNullable(selectPostById(postId));
    }

    @Select("""
            SELECT *
            FROM community_posts
            WHERE id = #{postId}
            LIMIT 1
            FOR UPDATE
            """)
    CommunityPost selectPostByIdForUpdate(@Param("postId") Long postId);

    default Optional<CommunityPost> findPostByIdForUpdate(Long postId) {
        return Optional.ofNullable(selectPostByIdForUpdate(postId));
    }

    @Select("""
            <script>
            SELECT p.*,
                   u.public_code AS author_public_code,
                   COALESCE(NULLIF(TRIM(u.nickname), ''), NULLIF(TRIM(u.username), '')) AS author_nickname,
                   u.avatar_url AS author_avatar_url
            FROM community_posts p
            LEFT JOIN users u ON u.id = p.user_id AND u.is_deleted = 0
            WHERE p.user_id = #{userId}
              AND p.status = 'PUBLISHED'
              AND (p.audit_status IS NULL OR p.audit_status = 'APPROVED')
            <if test="modality != null and modality.trim() != ''">
              AND p.modality = #{modality}
            </if>
            ORDER BY p.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CommunityPostDiscoverRow> findPublicByUserIdWithAuthor(@Param("userId") Long userId,
                                                                @Param("modality") String modality,
                                                                @Param("limit") int limit,
                                                                @Param("offset") int offset);

    @Select("""
            <script>
            SELECT *
            FROM community_posts
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            <if test="modality != null and modality.trim() != ''">
              AND modality = #{modality}
            </if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CommunityPost> findPublicByUserId(@Param("userId") Long userId,
                                           @Param("modality") String modality,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    @Select("""
            <script>
            SELECT p.*,
                   u.public_code AS author_public_code,
                   COALESCE(NULLIF(TRIM(u.nickname), ''), NULLIF(TRIM(u.username), '')) AS author_nickname,
                   u.avatar_url AS author_avatar_url
            FROM community_posts p
            LEFT JOIN users u ON u.id = p.user_id AND u.is_deleted = 0
            <if test="tag != null and tag.trim() != ''">
            INNER JOIN community_post_tags t ON t.post_id = p.id AND t.tag = #{tag}
            </if>
            WHERE p.status = 'PUBLISHED'
              AND (p.audit_status IS NULL OR p.audit_status = 'APPROVED')
            <if test="modality != null and modality.trim() != ''">
              AND p.modality = #{modality}
            </if>
            <if test="topic != null and topic.trim() != ''">
              AND p.topic = #{topic}
            </if>
            <if test="featured != null">
              AND p.featured = #{featured}
            </if>
            <choose>
              <when test="sort == 'POPULAR'">
                ORDER BY p.pinned DESC, p.like_count DESC, p.favorite_count DESC, p.id DESC
              </when>
              <when test="sort == 'FAVORITES'">
                ORDER BY p.pinned DESC, p.favorite_count DESC, p.id DESC
              </when>
              <when test="sort == 'SAME_STYLE'">
                ORDER BY p.pinned DESC, p.same_style_count DESC, p.id DESC
              </when>
              <when test="sort == 'VIEWS'">
                ORDER BY p.pinned DESC, p.view_count DESC, p.id DESC
              </when>
              <otherwise>
                ORDER BY p.pinned DESC, p.featured DESC, p.id DESC
              </otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CommunityPostDiscoverRow> discoverWithAuthor(@Param("modality") String modality,
                                                      @Param("tag") String tag,
                                                      @Param("topic") String topic,
                                                      @Param("sort") String sort,
                                                      @Param("featured") Boolean featured,
                                                      @Param("limit") int limit,
                                                      @Param("offset") int offset);

    @Select("""
            <script>
            SELECT p.*
            FROM community_posts p
            <if test="tag != null and tag.trim() != ''">
            INNER JOIN community_post_tags t ON t.post_id = p.id AND t.tag = #{tag}
            </if>
            WHERE p.status = 'PUBLISHED'
              AND (p.audit_status IS NULL OR p.audit_status = 'APPROVED')
            <if test="keyword != null and keyword.trim() != ''">
              AND (p.title LIKE CONCAT('%', #{keyword}, '%')
                OR p.description LIKE CONCAT('%', #{keyword}, '%')
                OR p.tool_name LIKE CONCAT('%', #{keyword}, '%')
                OR p.tool_code LIKE CONCAT('%', #{keyword}, '%')
                OR p.prompt_snapshot LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="toolCode != null and toolCode.trim() != ''">
              AND p.tool_code = #{toolCode}
            </if>
            <if test="modality != null and modality.trim() != ''">
              AND p.modality = #{modality}
            </if>
            <if test="topic != null and topic.trim() != ''">
              AND p.topic = #{topic}
            </if>
            <if test="featured != null">
              AND p.featured = #{featured}
            </if>
            <choose>
              <when test="sort == 'POPULAR'">
                ORDER BY p.pinned DESC, p.like_count DESC, p.favorite_count DESC, p.id DESC
              </when>
              <when test="sort == 'FAVORITES'">
                ORDER BY p.pinned DESC, p.favorite_count DESC, p.id DESC
              </when>
              <when test="sort == 'SAME_STYLE'">
                ORDER BY p.pinned DESC, p.same_style_count DESC, p.id DESC
              </when>
              <when test="sort == 'VIEWS'">
                ORDER BY p.pinned DESC, p.view_count DESC, p.id DESC
              </when>
              <when test="sort == 'QUALITY'">
                ORDER BY p.pinned DESC, p.featured DESC, p.quality_score DESC, p.like_count DESC, p.favorite_count DESC, p.same_style_count DESC, p.id DESC
              </when>
              <otherwise>
                ORDER BY p.pinned DESC, p.featured DESC, p.created_at DESC, p.id DESC
              </otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CommunityPost> discover(@Param("modality") String modality,
                                 @Param("tag") String tag,
                                 @Param("topic") String topic,
                                 @Param("keyword") String keyword,
                                 @Param("toolCode") String toolCode,
                                 @Param("sort") String sort,
                                 @Param("featured") Boolean featured,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM community_posts p
            <if test="tag != null and tag.trim() != ''">
            INNER JOIN community_post_tags t ON t.post_id = p.id AND t.tag = #{tag}
            </if>
            WHERE p.status = 'PUBLISHED'
              AND (p.audit_status IS NULL OR p.audit_status = 'APPROVED')
            <if test="keyword != null and keyword.trim() != ''">
              AND (p.title LIKE CONCAT('%', #{keyword}, '%')
                OR p.description LIKE CONCAT('%', #{keyword}, '%')
                OR p.tool_name LIKE CONCAT('%', #{keyword}, '%')
                OR p.tool_code LIKE CONCAT('%', #{keyword}, '%')
                OR p.prompt_snapshot LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="toolCode != null and toolCode.trim() != ''">
              AND p.tool_code = #{toolCode}
            </if>
            <if test="modality != null and modality.trim() != ''">
              AND p.modality = #{modality}
            </if>
            <if test="topic != null and topic.trim() != ''">
              AND p.topic = #{topic}
            </if>
            <if test="featured != null">
              AND p.featured = #{featured}
            </if>
            </script>
            """)
    long countDiscover(@Param("modality") String modality,
                       @Param("tag") String tag,
                       @Param("topic") String topic,
                       @Param("keyword") String keyword,
                       @Param("toolCode") String toolCode,
                       @Param("featured") Boolean featured);

    @Select("""
            SELECT topic AS name, COUNT(*) AS postCount
            FROM community_posts
            WHERE status = 'PUBLISHED'
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
              AND topic IS NOT NULL
              AND topic != ''
            GROUP BY topic
            ORDER BY postCount DESC, MAX(id) DESC
            LIMIT #{limit}
            """)
    List<CommunityTopicResponse> findTopTopics(@Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM community_posts
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            <if test="modality != null and modality.trim() != ''">
              AND modality = #{modality}
            </if>
            </script>
            """)
    long countPublicByUserId(@Param("userId") Long userId, @Param("modality") String modality);

    @Select("""
            SELECT COALESCE(SUM(like_count), 0)
            FROM community_posts
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            """)
    long sumLikesByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(favorite_count), 0)
            FROM community_posts
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            """)
    long sumFavoritesByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(same_style_count), 0)
            FROM community_posts
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            """)
    long sumSameStyleByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM community_posts
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND featured = 1
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            """)
    long countFeaturedByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM community_posts
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND featured = 1
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            ORDER BY pinned DESC, last_featured_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<CommunityPost> findFeaturedByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Update("""
            UPDATE community_posts
            SET status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{postId}
              AND user_id = #{userId}
              AND status IN ('PUBLISHED', 'UNPUBLISHED')
            """)
    int updateOwnerStatus(@Param("postId") Long postId, @Param("userId") Long userId, @Param("status") String status);

    @Update("""
            UPDATE community_posts
            SET status = 'UNPUBLISHED', updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
            """)
    int unpublishByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE community_posts
            SET title = #{title},
                description = #{description},
                prompt_visible = #{promptVisible},
                topic = #{topic},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{postId}
              AND user_id = #{userId}
              AND status IN ('PUBLISHED', 'UNPUBLISHED')
            """)
    int updateOwnerMetadata(@Param("postId") Long postId,
                            @Param("userId") Long userId,
                            @Param("title") String title,
                            @Param("description") String description,
                            @Param("promptVisible") boolean promptVisible,
                            @Param("topic") String topic);

    @Update("""
            UPDATE community_posts
            SET prompt_visible = #{promptVisible},
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND status = 'PUBLISHED'
              AND (audit_status IS NULL OR audit_status = 'APPROVED')
            """)
    int updatePromptVisibleByUserId(@Param("userId") Long userId,
                                    @Param("promptVisible") boolean promptVisible);

    @Update("""
            UPDATE community_posts
            SET status = #{status},
                audit_status = #{auditStatus},
                audit_reason = #{reason},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{postId}
            """)
    int updateAdminStatus(@Param("postId") Long postId,
                          @Param("status") String status,
                          @Param("auditStatus") String auditStatus,
                          @Param("reason") String reason);

    @Update("""
            UPDATE community_posts
            SET featured = #{featured},
                last_featured_at = CASE WHEN #{featured} THEN CURRENT_TIMESTAMP ELSE last_featured_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{postId}
            """)
    int updateFeatured(@Param("postId") Long postId, @Param("featured") boolean featured);

    @Update("""
            UPDATE community_posts
            SET pinned = #{pinned}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{postId}
            """)
    int updatePinned(@Param("postId") Long postId, @Param("pinned") boolean pinned);

    @Update("""
            UPDATE community_posts
            SET topic = #{topic}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{postId}
            """)
    int updateTopic(@Param("postId") Long postId, @Param("topic") String topic);

    @Update("""
            UPDATE community_posts
            SET view_count = view_count + 1, updated_at = updated_at
            WHERE id = #{postId}
              AND status = 'PUBLISHED'
            """)
    int incrementViews(@Param("postId") Long postId);

    @Update("""
            UPDATE community_posts
            SET detail_click_count = detail_click_count + 1, updated_at = updated_at
            WHERE id = #{postId}
              AND status = 'PUBLISHED'
            """)
    int incrementDetailClicks(@Param("postId") Long postId);

    @Update("""
            UPDATE community_posts
            SET share_count = share_count + 1, updated_at = updated_at
            WHERE id = #{postId}
              AND status = 'PUBLISHED'
            """)
    int incrementShares(@Param("postId") Long postId);

    @Update("""
            UPDATE community_posts
            SET same_style_count = same_style_count + 1, updated_at = updated_at
            WHERE id = #{postId}
              AND status = 'PUBLISHED'
            """)
    int incrementSameStyle(@Param("postId") Long postId);

    @Insert("""
            INSERT INTO community_post_likes (post_id, user_id)
            SELECT #{postId}, #{userId}
            WHERE NOT EXISTS (
              SELECT 1 FROM community_post_likes WHERE post_id = #{postId} AND user_id = #{userId}
            )
            """)
    int insertLike(@Param("postId") Long postId, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM community_post_likes
            WHERE post_id = #{postId}
              AND user_id = #{userId}
            """)
    int deleteLike(@Param("postId") Long postId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO community_post_favorites (post_id, user_id)
            SELECT #{postId}, #{userId}
            WHERE NOT EXISTS (
              SELECT 1 FROM community_post_favorites WHERE post_id = #{postId} AND user_id = #{userId}
            )
            """)
    int insertFavorite(@Param("postId") Long postId, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM community_post_favorites
            WHERE post_id = #{postId}
              AND user_id = #{userId}
            """)
    int deleteFavorite(@Param("postId") Long postId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM community_post_likes
            WHERE post_id = #{postId}
              AND user_id = #{userId}
            """)
    int countLike(@Param("postId") Long postId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM community_post_favorites
            WHERE post_id = #{postId}
              AND user_id = #{userId}
            """)
    int countFavorite(@Param("postId") Long postId, @Param("userId") Long userId);

    @Update("""
            UPDATE community_posts
            SET like_count = (
                SELECT COUNT(*) FROM community_post_likes WHERE post_id = #{postId}
            )
            WHERE id = #{postId}
            """)
    int refreshLikeCount(@Param("postId") Long postId);

    @Update("""
            UPDATE community_posts
            SET favorite_count = (
                SELECT COUNT(*) FROM community_post_favorites WHERE post_id = #{postId}
            )
            WHERE id = #{postId}
            """)
    int refreshFavoriteCount(@Param("postId") Long postId);

    @Update("""
            UPDATE community_posts
            SET quality_score =
                (CASE WHEN featured = 1 THEN 100 ELSE 0 END)
                + (CASE WHEN pinned = 1 THEN 200 ELSE 0 END)
                + (CASE WHEN cover_url IS NOT NULL AND cover_url != '' THEN 20 ELSE 0 END)
                + (CASE WHEN title IS NOT NULL AND title != '' THEN 10 ELSE 0 END)
                + (CASE WHEN prompt_visible = 1 AND prompt_snapshot IS NOT NULL AND prompt_snapshot != '' THEN 30 ELSE 0 END)
                + (CASE WHEN topic IS NOT NULL AND topic != '' THEN 15 ELSE 0 END)
                + (CASE WHEN EXISTS (SELECT 1 FROM community_post_tags WHERE post_id = #{postId}) THEN 10 ELSE 0 END)
                + LEAST(COALESCE(like_count, 0), 100)
                + LEAST(COALESCE(favorite_count, 0) * 2, 160)
                + LEAST(COALESCE(same_style_count, 0) * 3, 240)
                + LEAST(COALESCE(detail_click_count, 0), 120)
                + LEAST(COALESCE(share_count, 0) * 3, 90),
                updated_at = updated_at
            WHERE id = #{postId}
            """)
    int refreshQualityScore(@Param("postId") Long postId);

    @Select("""
            <script>
            SELECT *
            FROM community_posts
            WHERE 1 = 1
            <if test="userId != null">
              AND user_id = #{userId}
            </if>
            <if test="status != null and status.trim() != ''">
              AND status = #{status}
            </if>
            <if test="auditStatus != null and auditStatus.trim() != ''">
              AND audit_status = #{auditStatus}
            </if>
            <if test="modality != null and modality.trim() != ''">
              AND modality = #{modality}
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND (title LIKE CONCAT('%', #{keyword}, '%')
                OR description LIKE CONCAT('%', #{keyword}, '%')
                OR tool_name LIKE CONCAT('%', #{keyword}, '%')
                OR tool_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="topic != null and topic.trim() != ''">
              AND topic = #{topic}
            </if>
            <if test="featured != null">
              AND featured = #{featured}
            </if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CommunityPost> findForAdmin(@Param("userId") Long userId,
                                     @Param("status") String status,
                                     @Param("modality") String modality,
                                     @Param("keyword") String keyword,
                                     @Param("topic") String topic,
                                     @Param("featured") Boolean featured,
                                     @Param("auditStatus") String auditStatus,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM community_posts
            WHERE 1 = 1
            <if test="userId != null">
              AND user_id = #{userId}
            </if>
            <if test="status != null and status.trim() != ''">
              AND status = #{status}
            </if>
            <if test="auditStatus != null and auditStatus.trim() != ''">
              AND audit_status = #{auditStatus}
            </if>
            <if test="modality != null and modality.trim() != ''">
              AND modality = #{modality}
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND (title LIKE CONCAT('%', #{keyword}, '%')
                OR description LIKE CONCAT('%', #{keyword}, '%')
                OR tool_name LIKE CONCAT('%', #{keyword}, '%')
                OR tool_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="topic != null and topic.trim() != ''">
              AND topic = #{topic}
            </if>
            <if test="featured != null">
              AND featured = #{featured}
            </if>
            </script>
            """)
    long countForAdmin(@Param("userId") Long userId,
                       @Param("status") String status,
                       @Param("modality") String modality,
                       @Param("keyword") String keyword,
                       @Param("topic") String topic,
                       @Param("featured") Boolean featured,
                       @Param("auditStatus") String auditStatus);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM community_posts
            WHERE 1 = 1
            <if test="status != null and status.trim() != ''">
              AND status = #{status}
            </if>
            <if test="auditStatus != null and auditStatus.trim() != ''">
              AND audit_status = #{auditStatus}
            </if>
            </script>
            """)
    long countForStats(@Param("status") String status, @Param("auditStatus") String auditStatus);

    @Insert("""
            INSERT INTO community_post_tags (post_id, tag)
            SELECT #{postId}, #{tag}
            WHERE NOT EXISTS (
              SELECT 1 FROM community_post_tags WHERE post_id = #{postId} AND tag = #{tag}
            )
            """)
    int insertTag(@Param("postId") Long postId, @Param("tag") String tag);

    @Delete("""
            DELETE FROM community_post_tags
            WHERE post_id = #{postId}
            """)
    int deleteTags(@Param("postId") Long postId);

    @Select("""
            SELECT tag
            FROM community_post_tags
            WHERE post_id = #{postId}
            ORDER BY id ASC
            """)
    List<String> findTags(@Param("postId") Long postId);

    @Select("""
            SELECT *
            FROM community_posts
            WHERE UPPER(modality) = 'AUDIO'
              AND (media_url IS NULL OR media_url = '')
            ORDER BY id ASC
            LIMIT 200
            """)
    List<CommunityPost> findAudioPostsNeedingMediaBackfill();

    @Update("""
            UPDATE community_posts
            SET cover_url = #{coverUrl},
                media_url = #{mediaUrl}
            WHERE id = #{postId}
            """)
    int updateAudioMedia(@Param("postId") Long postId,
                         @Param("coverUrl") String coverUrl,
                         @Param("mediaUrl") String mediaUrl);

    @Update("""
            UPDATE community_posts
            SET cover_url = #{coverUrl},
                media_url = #{mediaUrl},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{postId}
            """)
    int updateMedia(@Param("postId") Long postId,
                    @Param("coverUrl") String coverUrl,
                    @Param("mediaUrl") String mediaUrl);

    @Select("""
            <script>
            SELECT post_id
            FROM community_post_likes
            WHERE user_id = #{userId}
              AND post_id IN
            <foreach item="id" collection="postIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> batchFindLikedPostIds(@Param("postIds") List<Long> postIds, @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT post_id
            FROM community_post_favorites
            WHERE user_id = #{userId}
              AND post_id IN
            <foreach item="id" collection="postIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> batchFindFavoritedPostIds(@Param("postIds") List<Long> postIds, @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT post_id, tag
            FROM community_post_tags
            WHERE post_id IN
            <foreach item="id" collection="postIds" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id ASC
            </script>
            """)
    List<java.util.Map<String, Object>> batchFindTagRows(@Param("postIds") List<Long> postIds);

    @Select("""
            <script>
            SELECT *
            FROM community_posts
            WHERE task_id IN
            <foreach item="id" collection="taskIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<CommunityPost> batchFindByTaskIds(@Param("taskIds") List<Long> taskIds);
}
