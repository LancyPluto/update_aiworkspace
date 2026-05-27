package com.aiminilab.aitoolmarket.market.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_market_tools")
public class AiMarketTool {

    @TableId
    private Long id;
    private String toolId;
    private String name;
    private String iconUrl;
    private String description;
    private Boolean enabled;
    private Integer sortOrder;
    private String primaryColor;
    private String welcomeMessage;
    private String capabilitiesJson;
    private Long modelConfigId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField("is_deleted")
    @TableLogic(value = "0", delval = "1")
    private Boolean deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
}
