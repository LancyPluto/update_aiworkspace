package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolModelBinding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ToolModelBindingMapper {

    @Select("""
            SELECT id, tool_id, model_config_id, is_default, sort_order, created_at, updated_at
            FROM tool_model_bindings
            WHERE tool_id = #{toolId}
            ORDER BY is_default DESC, sort_order ASC, id ASC
            """)
    List<ToolModelBinding> findByToolId(@Param("toolId") Long toolId);

    @Select("""
            SELECT COUNT(1)
            FROM tool_model_bindings
            WHERE tool_id = #{toolId}
            """)
    int countByToolId(@Param("toolId") Long toolId);

    @Select("""
            SELECT COUNT(1)
            FROM tool_model_bindings
            WHERE tool_id = #{toolId}
              AND model_config_id = #{modelConfigId}
            """)
    int countByToolIdAndModelConfigId(@Param("toolId") Long toolId,
                                      @Param("modelConfigId") Long modelConfigId);

    @Delete("DELETE FROM tool_model_bindings WHERE tool_id = #{toolId}")
    void deleteByToolId(@Param("toolId") Long toolId);

    @Insert("""
            INSERT INTO tool_model_bindings(tool_id, model_config_id, is_default, sort_order, created_at, updated_at)
            VALUES(#{toolId}, #{modelConfigId}, #{isDefault}, #{sortOrder}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    void insertBinding(@Param("toolId") Long toolId,
                       @Param("modelConfigId") Long modelConfigId,
                       @Param("isDefault") boolean isDefault,
                       @Param("sortOrder") int sortOrder);
}
