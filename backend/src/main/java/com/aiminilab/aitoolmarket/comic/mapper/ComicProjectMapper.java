package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicProject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ComicProjectMapper extends BaseMapper<ComicProject> {
    @Select("SELECT * FROM comic_projects WHERE id = #{id} AND user_id = #{userId} AND status <> 'DELETED' LIMIT 1")
    ComicProject selectOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM comic_projects WHERE id = #{id} AND user_id = #{userId} AND status <> 'DELETED' LIMIT 1 FOR UPDATE")
    ComicProject selectOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM comic_projects WHERE user_id = #{userId} AND status <> 'DELETED' ORDER BY updated_at DESC, id DESC")
    List<ComicProject> selectByUser(@Param("userId") Long userId);

    @Update("""
            UPDATE comic_projects
            SET title = #{title}, description = #{description}, aspect_ratio = #{aspectRatio},
                visual_style = #{visualStyle}, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND revision = #{expectedRevision} AND status <> 'DELETED'
            """)
    int updateMetadata(@Param("id") Long id, @Param("userId") Long userId,
                       @Param("expectedRevision") Long expectedRevision, @Param("title") String title,
                       @Param("description") String description, @Param("aspectRatio") String aspectRatio,
                       @Param("visualStyle") String visualStyle);

    @Update("""
            UPDATE comic_projects
            SET status = 'DELETED', deleted_at = CURRENT_TIMESTAMP, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND status <> 'DELETED'
            """)
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);
}
