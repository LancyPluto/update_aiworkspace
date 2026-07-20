package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicScene;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ComicSceneMapper extends BaseMapper<ComicScene> {
    @Select("SELECT * FROM comic_scenes WHERE project_id = #{projectId} AND status = 'ACTIVE' ORDER BY id")
    List<ComicScene> selectByProject(@Param("projectId") Long projectId);
}
