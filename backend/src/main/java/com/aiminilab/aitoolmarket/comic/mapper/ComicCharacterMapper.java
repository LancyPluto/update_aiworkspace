package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicCharacter;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ComicCharacterMapper extends BaseMapper<ComicCharacter> {
    @Select("SELECT * FROM comic_characters WHERE project_id = #{projectId} AND status = 'ACTIVE' ORDER BY id")
    List<ComicCharacter> selectByProject(@Param("projectId") Long projectId);
}
