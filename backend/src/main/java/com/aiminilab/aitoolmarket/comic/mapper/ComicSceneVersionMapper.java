package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicSceneVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ComicSceneVersionMapper extends BaseMapper<ComicSceneVersion> {
    @Select("SELECT * FROM comic_scene_versions WHERE scene_id = #{sceneId} ORDER BY version_no DESC")
    List<ComicSceneVersion> selectByScene(@Param("sceneId") Long sceneId);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM comic_scene_versions WHERE scene_id = #{sceneId}")
    int maxVersionNo(@Param("sceneId") Long sceneId);

    @Select("""
            SELECT COUNT(*) FROM comic_scene_versions
            WHERE scene_id = #{sceneId} AND status IN ('DRAFT', 'GENERATING', 'READY')
            """)
    int countReusable(@Param("sceneId") Long sceneId);

    @Select("SELECT * FROM comic_scene_versions WHERE id = #{versionId} FOR UPDATE")
    ComicSceneVersion selectByIdForUpdate(@Param("versionId") Long versionId);

    @Update("UPDATE comic_scene_versions SET status = 'GENERATING' WHERE id = #{versionId} AND status IN ('DRAFT', 'FAILED')")
    int markGenerating(@Param("versionId") Long versionId);

    @Update("""
            UPDATE comic_scene_versions SET anchor_image_url = #{anchorImageUrl}, status = 'READY'
            WHERE id = #{versionId} AND status IN ('DRAFT', 'GENERATING', 'FAILED')
            """)
    int markReady(@Param("versionId") Long versionId,
                  @Param("anchorImageUrl") String anchorImageUrl);

    @Update("UPDATE comic_scene_versions SET status = 'FAILED' WHERE id = #{versionId} AND status = 'GENERATING'")
    int markFailed(@Param("versionId") Long versionId);
}
