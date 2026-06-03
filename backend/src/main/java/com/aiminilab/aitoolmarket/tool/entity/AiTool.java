package com.aiminilab.aitoolmarket.tool.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ai_tools")
public class AiTool {

    @TableId
    private Long id;
    private String toolCode;
    private String toolName;
    private Long categoryId;
    @TableField(exist = false)
    private String categoryName;
    @TableField(exist = false)
    private String categoryCode;
    private String description;
    private String coverUrl;
    private String toolType;
    private String inputModality;
    private String outputModality;
    private String configNote;
    private String status;
    private Integer estimatedCreditCost;
    private Long modelConfigId;
    private Long templateId;
    private String executionHandler;
    @TableField(exist = false)
    private String modelConfigName;
    @TableField(exist = false)
    private String modelName;
    private Long createdBy;
    private Long updatedBy;
    @TableField("is_deleted")
    @TableLogic(value = "0", delval = "1")
    private Boolean deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getToolCode() {
        return toolCode;
    }

    public void setToolCode(String toolCode) {
        this.toolCode = toolCode;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public String getInputModality() {
        return inputModality;
    }

    public void setInputModality(String inputModality) {
        this.inputModality = inputModality;
    }

    public String getOutputModality() {
        return outputModality;
    }

    public void setOutputModality(String outputModality) {
        this.outputModality = outputModality;
    }

    public String getConfigNote() {
        return configNote;
    }

    public void setConfigNote(String configNote) {
        this.configNote = configNote;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getEstimatedCreditCost() {
        return estimatedCreditCost;
    }

    public void setEstimatedCreditCost(Integer estimatedCreditCost) {
        this.estimatedCreditCost = estimatedCreditCost;
    }

    public Long getModelConfigId() {
        return modelConfigId;
    }

    public void setModelConfigId(Long modelConfigId) {
        this.modelConfigId = modelConfigId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getExecutionHandler() {
        return executionHandler;
    }

    public void setExecutionHandler(String executionHandler) {
        this.executionHandler = executionHandler;
    }

    public String getModelConfigName() {
        return modelConfigName;
    }

    public void setModelConfigName(String modelConfigName) {
        this.modelConfigName = modelConfigName;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
