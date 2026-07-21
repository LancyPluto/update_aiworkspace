package com.aiminilab.aitoolmarket.learning.mapper;

import com.aiminilab.aitoolmarket.learning.entity.LearningTutorial;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface LearningTutorialMapper {
    @Select("""
            SELECT * FROM learning_tutorials
            WHERE (#{categoryId} IS NULL OR category_id = #{categoryId})
            ORDER BY category_id ASC, sort_order ASC, id ASC
            """)
    List<LearningTutorial> findAll(@Param("categoryId") Long categoryId);

    @Select("""
            SELECT tutorial.* FROM learning_tutorials tutorial
            JOIN learning_categories category ON category.id = tutorial.category_id
            WHERE tutorial.enabled = 1 AND category.enabled = 1
            ORDER BY category.sort_order ASC, category.id ASC, tutorial.sort_order ASC, tutorial.id ASC
            """)
    List<LearningTutorial> findVisible();

    @Select("SELECT * FROM learning_tutorials WHERE id = #{id}")
    LearningTutorial findById(@Param("id") Long id);

    @Select("SELECT COUNT(1) FROM learning_tutorials WHERE category_id = #{categoryId}")
    int countByCategoryId(@Param("categoryId") Long categoryId);

    @Insert("""
            INSERT INTO learning_tutorials(category_id, title, summary, cover_image_url, video_url,
                                           sort_order, enabled, created_at, updated_at)
            VALUES(#{categoryId}, #{title}, #{summary}, #{coverImageUrl}, #{videoUrl},
                   #{sortOrder}, #{enabled}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LearningTutorial tutorial);

    @Update("""
            UPDATE learning_tutorials
            SET category_id = #{categoryId}, title = #{title}, summary = #{summary},
                cover_image_url = #{coverImageUrl}, video_url = #{videoUrl},
                sort_order = #{sortOrder}, enabled = #{enabled}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int update(LearningTutorial tutorial);

    @Delete("DELETE FROM learning_tutorials WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
