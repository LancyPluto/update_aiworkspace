package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ToolCategoryMapper extends BaseMapper<ToolCategory> {

    @Insert("""
            INSERT INTO tool_categories (category_code, category_name, sort_order, status)
            VALUES
              ('text-to-image', '文生图', 10, 'ACTIVE'),
              ('text-to-video', '文生视频', 20, 'ACTIVE'),
              ('image-to-video', '图生视频', 30, 'ACTIVE'),
              ('copy-generation', '文案生成', 40, 'ACTIVE'),
              ('agent', '智能体', 50, 'ACTIVE')
            ON DUPLICATE KEY UPDATE category_name = VALUES(category_name), status = VALUES(status)
            """)
    void ensureDefaultCategory();

    @Update("""
            UPDATE tool_categories
            SET status = 'INACTIVE',
                category_name = CASE
                    WHEN category_code = 'copywriting' THEN 'Copywriting (deprecated)'
                    WHEN category_code = 'ai-image' THEN 'AI 生图 (deprecated)'
                    ELSE category_name
                END
            WHERE category_code IN ('copywriting', 'ai-image')
            """)
    void retireLegacyCategories();

    default List<ToolCategory> findActiveCategories() {
        return selectList(new LambdaQueryWrapper<ToolCategory>()
                .eq(ToolCategory::getStatus, "ACTIVE")
                .orderByAsc(ToolCategory::getSortOrder)
                .orderByAsc(ToolCategory::getId));
    }

    default List<ToolCategory> findAllCategories() {
        return selectList(new LambdaQueryWrapper<ToolCategory>()
                .orderByAsc(ToolCategory::getSortOrder)
                .orderByAsc(ToolCategory::getId));
    }
}
