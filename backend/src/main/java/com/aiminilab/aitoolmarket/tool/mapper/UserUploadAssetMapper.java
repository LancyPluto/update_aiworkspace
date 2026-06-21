package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.UserUploadAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface UserUploadAssetMapper extends BaseMapper<UserUploadAsset> {

    @Insert("""
            INSERT INTO user_upload_assets(user_id, file_id, asset_kind, original_filename, content_type,
                                           file_size, url, storage_path, status, created_at, updated_at)
            VALUES(#{asset.userId}, #{asset.fileId}, #{asset.assetKind}, #{asset.originalFilename}, #{asset.contentType},
                   #{asset.fileSize}, #{asset.url}, #{asset.storagePath}, #{asset.status}, #{asset.createdAt}, #{asset.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "asset.id")
    void insertAsset(@Param("asset") UserUploadAsset asset);

    @Select("""
            SELECT COUNT(*)
            FROM user_upload_assets
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND (#{kind} IS NULL OR #{kind} = '' OR asset_kind = #{kind})
            """)
    long countActiveByUser(@Param("userId") Long userId,
                           @Param("kind") String kind);

    @Select("""
            SELECT *
            FROM user_upload_assets
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND (#{kind} IS NULL OR #{kind} = '' OR asset_kind = #{kind})
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<UserUploadAsset> findRecentByUser(@Param("userId") Long userId,
                                           @Param("kind") String kind,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    @Select("""
            SELECT COUNT(1)
            FROM user_upload_assets
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND (
                url LIKE CONCAT('%/', #{relativeKey})
                OR storage_path LIKE CONCAT('%/', #{relativeKey})
              )
            """)
    long countActiveByUserAndRelativeKey(@Param("userId") Long userId,
                                         @Param("relativeKey") String relativeKey);

    @Update("""
            UPDATE user_upload_assets
            SET status = 'DELETED', updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId} AND status = 'ACTIVE'
            """)
    int softDelete(@Param("userId") Long userId,
                   @Param("id") Long id,
                   @Param("updatedAt") LocalDateTime updatedAt);
}
