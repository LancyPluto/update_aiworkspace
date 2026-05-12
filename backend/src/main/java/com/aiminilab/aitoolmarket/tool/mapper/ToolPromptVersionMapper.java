package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolPromptVersion;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface ToolPromptVersionMapper extends BaseMapper<ToolPromptVersion> {

    default List<ToolPromptVersion> findByPromptId(Long promptId) {
        return selectList(new LambdaQueryWrapper<ToolPromptVersion>()
                .eq(ToolPromptVersion::getPromptId, promptId)
                .orderByDesc(ToolPromptVersion::getId));
    }

    default Optional<ToolPromptVersion> findById(Long versionId) {
        return Optional.ofNullable(selectById(versionId));
    }

    @Update("""
            UPDATE tool_prompt_versions
            SET status = 'INACTIVE'
            WHERE prompt_id = #{promptId} AND id <> #{activeVersionId}
            """)
    void deactivateOtherVersions(@Param("promptId") Long promptId, @Param("activeVersionId") Long activeVersionId);

    @Update("""
            UPDATE tool_prompts
            SET active_version_id = #{activeVersionId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{promptId}
            """)
    void updatePromptActiveVersion(@Param("promptId") Long promptId, @Param("activeVersionId") Long activeVersionId);
}
