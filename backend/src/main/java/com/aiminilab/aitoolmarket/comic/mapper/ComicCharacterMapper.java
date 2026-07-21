package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicCharacter;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ComicCharacterMapper extends BaseMapper<ComicCharacter> {
    @Select("SELECT * FROM comic_characters WHERE project_id = #{projectId} AND status = 'ACTIVE' ORDER BY id")
    List<ComicCharacter> selectByProject(@Param("projectId") Long projectId);

    @Select("""
            SELECT * FROM comic_characters
            WHERE project_id = #{projectId}
              AND LOWER(REPLACE(TRIM(name), ' ', '')) = #{normalizedName}
            ORDER BY id
            LIMIT 1
            FOR UPDATE
            """)
    ComicCharacter selectByNormalizedNameForUpdate(@Param("projectId") Long projectId,
                                                   @Param("normalizedName") String normalizedName);
}
