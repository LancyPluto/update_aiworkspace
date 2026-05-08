package com.aiminilab.aitoolmarket.tool.entity;

public class AiTool {

    private Long id;
    private String toolCode;
    private String toolName;
    private Long categoryId;
    private String categoryName;
    private String description;
    private String coverUrl;
    private String status;
    private Integer estimatedCreditCost;

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
}
