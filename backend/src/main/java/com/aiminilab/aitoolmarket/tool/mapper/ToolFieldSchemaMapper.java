package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolFieldSchema;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.Optional;

public interface ToolFieldSchemaMapper extends BaseMapper<ToolFieldSchema> {

    default Long createActiveDefaultSchema(Long toolId, Long operatorId) {
        ToolFieldSchema schema = new ToolFieldSchema();
        schema.setToolId(toolId);
        schema.setSchemaVersion("v1.0.0");
        schema.setStatus("ACTIVE");
        schema.setCreatedBy(operatorId);
        insert(schema);
        return schema.getId();
    }

    default Optional<Long> findActiveSchemaId(Long toolId) {
        ToolFieldSchema schema = selectOne(new LambdaQueryWrapper<ToolFieldSchema>()
                .select(ToolFieldSchema::getId)
                .eq(ToolFieldSchema::getToolId, toolId)
                .eq(ToolFieldSchema::getStatus, "ACTIVE")
                .orderByDesc(ToolFieldSchema::getId)
                .last("LIMIT 1"));
        return Optional.ofNullable(schema).map(ToolFieldSchema::getId);
    }
}
