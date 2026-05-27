package com.aiminilab.aitoolmarket.tool.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tool_field_schema_items")
public class ToolFieldItem {

    @TableId
    private Long id;
    private Long schemaId;
    private String fieldKey;
    private String fieldName;
    private String fieldType;
    private String placeholder;
    private String optionsJson;
    private Boolean required;
    private Boolean executionRequired;
    private Boolean userRequired;
    private String defaultValue;
    private String agentFillStrategy;
    private String riskLevel;
    private Integer sortOrder;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSchemaId() {
        return schemaId;
    }

    public void setSchemaId(Long schemaId) {
        this.schemaId = schemaId;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public void setFieldKey(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public void setOptionsJson(String optionsJson) {
        this.optionsJson = optionsJson;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Boolean getExecutionRequired() {
        return executionRequired;
    }

    public void setExecutionRequired(Boolean executionRequired) {
        this.executionRequired = executionRequired;
    }

    public Boolean getUserRequired() {
        return userRequired;
    }

    public void setUserRequired(Boolean userRequired) {
        this.userRequired = userRequired;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getAgentFillStrategy() {
        return agentFillStrategy;
    }

    public void setAgentFillStrategy(String agentFillStrategy) {
        this.agentFillStrategy = agentFillStrategy;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
