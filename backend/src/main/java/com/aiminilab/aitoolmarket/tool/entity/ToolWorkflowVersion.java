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

    private String canonicalDslJson;

    private String dslVersion;

    private String nodeRegistryVersion;

    private String dslHash;

    private String inputSchemaSnapshotJson;

    private String dependencyManifestJson;

    private String billingPolicyJson;

    private String riskPolicyJson;

    private Long sourceDraftRevision;

    private LocalDateTime publishedAt;

    private Long publishedBy;

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

    public String getCanonicalDslJson() { return canonicalDslJson; }
    public void setCanonicalDslJson(String canonicalDslJson) { this.canonicalDslJson = canonicalDslJson; }

    public String getDslVersion() { return dslVersion; }
    public void setDslVersion(String dslVersion) { this.dslVersion = dslVersion; }

    public String getNodeRegistryVersion() { return nodeRegistryVersion; }
    public void setNodeRegistryVersion(String nodeRegistryVersion) { this.nodeRegistryVersion = nodeRegistryVersion; }

    public String getDslHash() { return dslHash; }
    public void setDslHash(String dslHash) { this.dslHash = dslHash; }

    public String getInputSchemaSnapshotJson() { return inputSchemaSnapshotJson; }
    public void setInputSchemaSnapshotJson(String inputSchemaSnapshotJson) { this.inputSchemaSnapshotJson = inputSchemaSnapshotJson; }

    public String getDependencyManifestJson() { return dependencyManifestJson; }
    public void setDependencyManifestJson(String dependencyManifestJson) { this.dependencyManifestJson = dependencyManifestJson; }

    public String getBillingPolicyJson() { return billingPolicyJson; }
    public void setBillingPolicyJson(String billingPolicyJson) { this.billingPolicyJson = billingPolicyJson; }

    public String getRiskPolicyJson() { return riskPolicyJson; }
    public void setRiskPolicyJson(String riskPolicyJson) { this.riskPolicyJson = riskPolicyJson; }

    public Long getSourceDraftRevision() { return sourceDraftRevision; }
    public void setSourceDraftRevision(Long sourceDraftRevision) { this.sourceDraftRevision = sourceDraftRevision; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public Long getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Long publishedBy) { this.publishedBy = publishedBy; }

    public String getSnapshotLabel() { return snapshotLabel; }
    public void setSnapshotLabel(String snapshotLabel) { this.snapshotLabel = snapshotLabel; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
