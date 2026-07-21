package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicCharacterVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ComicCharacterVersionMapper extends BaseMapper<ComicCharacterVersion> {
    @Select("SELECT * FROM comic_character_versions WHERE character_id = #{characterId} ORDER BY version_no DESC")
    List<ComicCharacterVersion> selectByCharacter(@Param("characterId") Long characterId);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM comic_character_versions WHERE character_id = #{characterId}")
    int maxVersionNo(@Param("characterId") Long characterId);

    @Select("""
            SELECT COUNT(*) FROM comic_character_versions
            WHERE character_id = #{characterId} AND status IN ('DRAFT', 'GENERATING', 'READY')
            """)
    int countReusable(@Param("characterId") Long characterId);

    @Select("SELECT * FROM comic_character_versions WHERE id = #{versionId} FOR UPDATE")
    ComicCharacterVersion selectByIdForUpdate(@Param("versionId") Long versionId);

    @Update("UPDATE comic_character_versions SET status = 'GENERATING' WHERE id = #{versionId} AND status IN ('DRAFT', 'FAILED')")
    int markGenerating(@Param("versionId") Long versionId);

    @Update("""
            UPDATE comic_character_versions
            SET front_image_url = #{frontImageUrl}, side_image_url = #{sideImageUrl},
                back_image_url = #{backImageUrl}, status = 'READY'
            WHERE id = #{versionId} AND status IN ('DRAFT', 'GENERATING', 'FAILED')
            """)
    int markReady(@Param("versionId") Long versionId,
                  @Param("frontImageUrl") String frontImageUrl,
                  @Param("sideImageUrl") String sideImageUrl,
                  @Param("backImageUrl") String backImageUrl);

    @Update("UPDATE comic_character_versions SET status = 'FAILED' WHERE id = #{versionId} AND status = 'GENERATING'")
    int markFailed(@Param("versionId") Long versionId);
}
