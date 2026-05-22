package com.aiminilab.aitoolmarket.tool.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("tool_workflow_versions")
public class ToolWorkflowVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Integer version;

    private String nodesJson;

    private String edgesJson;

    private String groupsJson;

    private String configJson;

    private String snapshotLabel;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getNodesJson() { return nodesJson; }
    public void setNodesJson(String nodesJson) { this.nodesJson = nodesJson; }

    public String getEdgesJson() { return edgesJson; }
    public void setEdgesJson(String edgesJson) { this.edgesJson = edgesJson; }

    public String getGroupsJson() { return groupsJson; }
    public void setGroupsJson(String groupsJson) { this.groupsJson = groupsJson; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public String getSnapshotLabel() { return snapshotLabel; }
    public void setSnapshotLabel(String snapshotLabel) { this.snapshotLabel = snapshotLabel; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
