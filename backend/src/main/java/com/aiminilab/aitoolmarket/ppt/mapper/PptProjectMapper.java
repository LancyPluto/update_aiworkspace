package com.aiminilab.aitoolmarket.ppt.mapper;

import com.aiminilab.aitoolmarket.ppt.entity.PptProject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PptProjectMapper extends BaseMapper<PptProject> {

    @Insert("""
            INSERT INTO ppt_projects (
              user_id, tool_id, title, topic, creation_type, language, aspect_ratio,
              page_count, status, engine_strategy, text_model_config_id,
              image_model_config_id, is_deleted, created_at, updated_at
            ) VALUES (
              #{project.userId}, #{project.toolId}, #{project.title}, #{project.topic},
              #{project.creationType}, #{project.language}, #{project.aspectRatio},
              #{project.pageCount}, #{project.status}, #{project.engineStrategy},
              #{project.textModelConfigId}, #{project.imageModelConfigId}, 0,
              #{project.createdAt}, #{project.updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "project.id")
    int insertProject(@Param("project") PptProject project);

    @Select("""
            SELECT *
            FROM ppt_projects
            WHERE id = #{id} AND user_id = #{userId} AND is_deleted = 0
            LIMIT 1
            """)
    PptProject findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM ppt_projects
            WHERE user_id = #{userId} AND is_deleted = 0
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PptProject> findByUser(@Param("userId") Long userId,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM ppt_projects
            WHERE user_id = #{userId} AND is_deleted = 0
            """)
    long countByUser(@Param("userId") Long userId);

    @Update("""
            UPDATE ppt_projects
            SET status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND is_deleted = 0
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("status") String status);

    @Update("""
            UPDATE ppt_projects
            SET text_model_config_id = #{textModelConfigId},
                image_model_config_id = #{imageModelConfigId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND is_deleted = 0
            """)
    int updateModelSelection(@Param("id") Long id,
                             @Param("userId") Long userId,
                             @Param("textModelConfigId") Long textModelConfigId,
                             @Param("imageModelConfigId") Long imageModelConfigId);

    @Update("""
            UPDATE ppt_projects
            SET is_deleted = 1, deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND is_deleted = 0
            """)
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);
}
