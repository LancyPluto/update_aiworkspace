package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicCharacterVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ComicCharacterVersionMapper extends BaseMapper<ComicCharacterVersion> {
    @Select("SELECT * FROM comic_character_versions WHERE character_id = #{characterId} ORDER BY version_no DESC")
    List<ComicCharacterVersion> selectByCharacter(@Param("characterId") Long characterId);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM comic_character_versions WHERE character_id = #{characterId}")
    int maxVersionNo(@Param("characterId") Long characterId);
}
