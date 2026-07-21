package com.aiminilab.aitoolmarket.learning.mapper;

import com.aiminilab.aitoolmarket.learning.entity.LearningCategory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface LearningCategoryMapper {
    @Select("SELECT * FROM learning_categories ORDER BY sort_order ASC, id ASC")
    List<LearningCategory> findAll();

    @Select("SELECT * FROM learning_categories WHERE enabled = 1 ORDER BY sort_order ASC, id ASC")
    List<LearningCategory> findEnabled();

    @Select("SELECT * FROM learning_categories WHERE id = #{id}")
    LearningCategory findById(@Param("id") Long id);

    @Insert("""
            INSERT INTO learning_categories(name, sort_order, enabled, created_at, updated_at)
            VALUES(#{name}, #{sortOrder}, #{enabled}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LearningCategory category);

    @Update("""
            UPDATE learning_categories
            SET name = #{name}, sort_order = #{sortOrder}, enabled = #{enabled}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int update(LearningCategory category);

    @Delete("DELETE FROM learning_categories WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
