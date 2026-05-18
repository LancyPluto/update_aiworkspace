package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolTemplateField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ToolTemplateFieldMapper extends BaseMapper<ToolTemplateField> {

    @Select("""
            SELECT * FROM tool_template_fields
            WHERE template_id = #{templateId} AND status = 'ACTIVE'
            ORDER BY sort_order ASC, id ASC
            """)
    List<ToolTemplateField> findByTemplateId(@Param("templateId") Long templateId);

    @Delete("DELETE FROM tool_template_fields WHERE template_id = #{templateId}")
    void deleteByTemplateId(@Param("templateId") Long templateId);
}
