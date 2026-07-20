package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicSceneVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ComicSceneVersionMapper extends BaseMapper<ComicSceneVersion> {
    @Select("SELECT * FROM comic_scene_versions WHERE scene_id = #{sceneId} ORDER BY version_no DESC")
    List<ComicSceneVersion> selectByScene(@Param("sceneId") Long sceneId);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM comic_scene_versions WHERE scene_id = #{sceneId}")
    int maxVersionNo(@Param("sceneId") Long sceneId);
}
