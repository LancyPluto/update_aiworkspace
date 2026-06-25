package com.aiminilab.aitoolmarket.community.mapper;

import com.aiminilab.aitoolmarket.community.entity.CommunityCollection;
import com.aiminilab.aitoolmarket.community.entity.CommunityPost;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CommunityCollectionMapper extends BaseMapper<CommunityCollection> {

    default Long insertAndReturnId(CommunityCollection collection) {
        insert(collection);
        return collection.getId();
    }

    @Select("""
            SELECT *
            FROM community_collections
            WHERE user_id = #{userId}
            ORDER BY default_collection DESC, id DESC
            """)
    List<CommunityCollection> findByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM community_collections
            WHERE user_id = #{userId}
              AND default_collection = 1
            LIMIT 1
            """)
    CommunityCollection findDefault(@Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM community_collections
            WHERE id = #{collectionId}
              AND user_id = #{userId}
            LIMIT 1
            """)
    CommunityCollection findOwned(@Param("collectionId") Long collectionId, @Param("userId") Long userId);

    @Update("""
            UPDATE community_collections
            SET name = #{name}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{collectionId}
              AND user_id = #{userId}
              AND default_collection = 0
            """)
    int rename(@Param("collectionId") Long collectionId, @Param("userId") Long userId, @Param("name") String name);

    @Delete("""
            DELETE FROM community_collections
            WHERE id = #{collectionId}
              AND user_id = #{userId}
              AND default_collection = 0
            """)
    int deleteOwned(@Param("collectionId") Long collectionId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO community_collection_items (collection_id, post_id, user_id)
            SELECT #{collectionId}, #{postId}, #{userId}
            WHERE NOT EXISTS (
              SELECT 1 FROM community_collection_items
              WHERE collection_id = #{collectionId} AND post_id = #{postId}
            )
            """)
    int addItem(@Param("collectionId") Long collectionId, @Param("postId") Long postId, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM community_collection_items
            WHERE collection_id = #{collectionId}
              AND post_id = #{postId}
              AND user_id = #{userId}
            """)
    int removeItem(@Param("collectionId") Long collectionId, @Param("postId") Long postId, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM community_collection_items
            WHERE collection_id = #{collectionId}
              AND user_id = #{userId}
            """)
    int removeAllItems(@Param("collectionId") Long collectionId, @Param("userId") Long userId);

    @Update("""
            UPDATE community_collections
            SET item_count = (
                SELECT COUNT(*) FROM community_collection_items WHERE collection_id = #{collectionId}
            ),
            updated_at = CURRENT_TIMESTAMP
            WHERE id = #{collectionId}
            """)
    int refreshItemCount(@Param("collectionId") Long collectionId);

    @Select("""
            SELECT p.*
            FROM community_collection_items i
            JOIN community_posts p ON p.id = i.post_id
            WHERE i.collection_id = #{collectionId}
              AND i.user_id = #{userId}
              AND p.status = 'PUBLISHED'
              AND (p.audit_status IS NULL OR p.audit_status = 'APPROVED')
            ORDER BY i.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<CommunityPost> findItems(@Param("collectionId") Long collectionId,
                                  @Param("userId") Long userId,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);
}
