package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ToolFieldItemMapper extends BaseMapper<ToolFieldItem> {

    @Select("""
            SELECT i.*
            FROM tool_field_schemas s
            JOIN tool_field_schema_items i ON i.schema_id = s.id
            WHERE s.tool_id = #{toolId} AND s.status = 'ACTIVE' AND i.status = 'ACTIVE'
            ORDER BY i.sort_order ASC, i.id ASC
            """)
    List<ToolFieldItem> findActiveFields(@Param("toolId") Long toolId);

    @Update("""
            UPDATE tool_field_schema_items
            SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
            WHERE schema_id = #{schemaId}
            """)
    void inactiveBySchemaId(@Param("schemaId") Long schemaId);

    default void createDefaultFields(Long schemaId) {
        insertField(schemaId, "productName", "产品名称", "text", "请输入产品名称", null, true, 1);
        insertField(schemaId, "targetCustomer", "目标用户", "textarea", "请输入目标用户", null, true, 2);
        insertField(schemaId, "style", "文案风格", "select", "请选择文案风格",
                "[{\"label\":\"种草\",\"value\":\"种草\"},{\"label\":\"专业\",\"value\":\"专业\"}]", true, 3);
    }

    default void replaceActiveFields(Long schemaId, List<ToolFieldItem> fields) {
        inactiveBySchemaId(schemaId);
        for (ToolFieldItem field : fields) {
            insertField(
                    schemaId,
                    field.getFieldKey(),
                    field.getFieldName(),
                    field.getFieldType(),
                    field.getPlaceholder(),
                    field.getOptionsJson(),
                    field.getRequired() != null && field.getRequired(),
                    field.getSortOrder()
            );
        }
    }

    default void insertField(Long schemaId, String fieldKey, String fieldName, String fieldType,
                             String placeholder, String optionsJson, boolean required, int sortOrder) {
        ToolFieldItem item = new ToolFieldItem();
        item.setSchemaId(schemaId);
        item.setFieldKey(fieldKey);
        item.setFieldName(fieldName);
        item.setFieldType(fieldType);
        item.setPlaceholder(placeholder);
        item.setOptionsJson(optionsJson);
        item.setRequired(required);
        item.setSortOrder(sortOrder);
        item.setStatus("ACTIVE");
        insert(item);
    }

    default List<ToolFieldItem> findBySchemaId(Long schemaId) {
        return selectList(new LambdaQueryWrapper<ToolFieldItem>()
                .eq(ToolFieldItem::getSchemaId, schemaId)
                .eq(ToolFieldItem::getStatus, "ACTIVE")
                .orderByAsc(ToolFieldItem::getSortOrder)
                .orderByAsc(ToolFieldItem::getId));
    }
}
