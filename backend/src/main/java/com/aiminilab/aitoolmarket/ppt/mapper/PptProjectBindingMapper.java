package com.aiminilab.aitoolmarket.ppt.mapper;

import com.aiminilab.aitoolmarket.ppt.entity.PptProjectBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PptProjectBindingMapper extends BaseMapper<PptProjectBinding> {

    @Select("""
            SELECT *
            FROM ppt_project_bindings
            WHERE id = #{id} AND user_id = #{userId}
            LIMIT 1
            """)
    PptProjectBinding findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    default Optional<PptProjectBinding> findOptional(Long id, Long userId) {
        return Optional.ofNullable(findByIdAndUser(id, userId));
    }

    @Select("""
            SELECT *
            FROM ppt_project_bindings
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC
            """)
    List<PptProjectBinding> findByUserId(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO ppt_project_bindings (
              user_id, tool_id, banana_project_id, creation_type, title, status, created_at, updated_at
            ) VALUES (
              #{binding.userId}, #{binding.toolId}, #{binding.bananaProjectId}, #{binding.creationType},
              #{binding.title}, #{binding.status}, #{binding.createdAt}, #{binding.updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "binding.id")
    int insertBinding(@Param("binding") PptProjectBinding binding);

    @Update("""
            UPDATE ppt_project_bindings
            SET status = #{status}, title = #{title}, updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("status") String status,
                     @Param("title") String title,
                     @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("""
            DELETE FROM ppt_project_bindings
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
}
