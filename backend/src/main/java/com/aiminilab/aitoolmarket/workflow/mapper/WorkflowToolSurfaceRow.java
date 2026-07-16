package com.aiminilab.aitoolmarket.workflow.mapper;

public class WorkflowToolSurfaceRow {

    private Long id;
    private String toolCode;
    private String toolName;
    private String description;
    private String categoryName;
    private String coverUrl;
    private String status;
    private Integer estimatedCreditCost;
    private Integer minimumRequiredCredits;
    private String billingMode;
    private String versionConfigJson;
    private Long workflowId;
    private Long publishedVersionId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToolCode() { return toolCode; }
    public void setToolCode(String toolCode) { this.toolCode = toolCode; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getEstimatedCreditCost() { return estimatedCreditCost; }
    public void setEstimatedCreditCost(Integer estimatedCreditCost) { this.estimatedCreditCost = estimatedCreditCost; }
    public Integer getMinimumRequiredCredits() { return minimumRequiredCredits; }
    public void setMinimumRequiredCredits(Integer minimumRequiredCredits) { this.minimumRequiredCredits = minimumRequiredCredits; }
    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String billingMode) { this.billingMode = billingMode; }
    public String getVersionConfigJson() { return versionConfigJson; }
    public void setVersionConfigJson(String versionConfigJson) { this.versionConfigJson = versionConfigJson; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
    public Long getPublishedVersionId() { return publishedVersionId; }
    public void setPublishedVersionId(Long publishedVersionId) { this.publishedVersionId = publishedVersionId; }
}
