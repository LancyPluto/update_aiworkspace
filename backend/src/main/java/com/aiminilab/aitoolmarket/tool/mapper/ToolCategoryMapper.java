package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

import java.util.List;

public interface ToolCategoryMapper extends BaseMapper<ToolCategory> {

    @Insert("""
            INSERT INTO tool_categories (category_code, category_name, sort_order, status)
            VALUES ('copywriting', 'Copywriting', 1, 'ACTIVE')
            ON DUPLICATE KEY UPDATE category_name = VALUES(category_name), status = VALUES(status)
            """)
    void ensureDefaultCategory();

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
