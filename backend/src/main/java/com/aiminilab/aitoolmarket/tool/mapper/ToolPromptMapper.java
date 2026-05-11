package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolPrompt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;
import java.util.Optional;

public interface ToolPromptMapper extends BaseMapper<ToolPrompt> {

    default List<ToolPrompt> findByToolId(Long toolId) {
        return selectList(new LambdaQueryWrapper<ToolPrompt>()
                .eq(ToolPrompt::getToolId, toolId)
                .orderByAsc(ToolPrompt::getId));
    }

    default Optional<ToolPrompt> findById(Long promptId) {
        return Optional.ofNullable(selectById(promptId));
    }
}
