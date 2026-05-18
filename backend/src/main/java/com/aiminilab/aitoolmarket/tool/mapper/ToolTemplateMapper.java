package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

public interface ToolTemplateMapper extends BaseMapper<ToolTemplate> {

    @Select("""
            <script>
            SELECT * FROM tool_templates
            WHERE status = 'ACTIVE'
            <if test="toolType != null and toolType != ''">
              AND tool_type = #{toolType}
            </if>
            <if test="executionHandler != null and executionHandler != ''">
              AND execution_handler = #{executionHandler}
            </if>
            ORDER BY sort_order ASC, id ASC
            </script>
            """)
    List<ToolTemplate> findActive(@Param("toolType") String toolType,
                                  @Param("executionHandler") String executionHandler);

    @Select("""
            SELECT * FROM tool_templates
            ORDER BY sort_order ASC, id ASC
            """)
    List<ToolTemplate> findAllOrdered();

    @Select("""
            SELECT * FROM tool_templates
            WHERE template_code = #{templateCode}
            LIMIT 1
            """)
    ToolTemplate selectByCode(@Param("templateCode") String templateCode);

    default Optional<ToolTemplate> findByCode(String templateCode) {
        return Optional.ofNullable(selectByCode(templateCode));
    }
}
