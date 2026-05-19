package com.aiminilab.aitoolmarket.tool.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tool_templates")
public class ToolTemplate {

    @TableId
    private Long id;
    private String templateCode;
    private String templateName;
    private String toolType;
    private String executionHandler;
    private String inputModality;
    private String outputModality;
    private String configNote;
    private String defaultSystemPrompt;
    private String defaultUserPromptTemplate;
    private String defaultOutputFormat;
    private String handlerConfigJson;
    private Long suggestedModelConfigId;
    private String status;
    private Integer sortOrder;
    @TableField("is_system")
    private Boolean systemTemplate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public String getExecutionHandler() {
        return executionHandler;
    }

    public void setExecutionHandler(String executionHandler) {
        this.executionHandler = executionHandler;
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

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }

    public String getDefaultUserPromptTemplate() {
        return defaultUserPromptTemplate;
    }

    public void setDefaultUserPromptTemplate(String defaultUserPromptTemplate) {
        this.defaultUserPromptTemplate = defaultUserPromptTemplate;
    }

    public String getDefaultOutputFormat() {
        return defaultOutputFormat;
    }

    public void setDefaultOutputFormat(String defaultOutputFormat) {
        this.defaultOutputFormat = defaultOutputFormat;
    }

    public String getHandlerConfigJson() {
        return handlerConfigJson;
    }

    public void setHandlerConfigJson(String handlerConfigJson) {
        this.handlerConfigJson = handlerConfigJson;
    }

    public Long getSuggestedModelConfigId() {
        return suggestedModelConfigId;
    }

    public void setSuggestedModelConfigId(Long suggestedModelConfigId) {
        this.suggestedModelConfigId = suggestedModelConfigId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getSystemTemplate() {
        return systemTemplate;
    }

    public void setSystemTemplate(Boolean systemTemplate) {
        this.systemTemplate = systemTemplate;
    }
}
