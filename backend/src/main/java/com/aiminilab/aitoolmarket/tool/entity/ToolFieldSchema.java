package com.aiminilab.aitoolmarket.tool.entity;

<<<<<<< HEAD
import java.time.LocalDateTime;

public class ToolFieldSchema {
=======
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tool_field_schemas")
public class ToolFieldSchema {

    @TableId
>>>>>>> origin/feature/backend-core
    private Long id;
    private Long toolId;
    private String schemaVersion;
    private String status;
<<<<<<< HEAD
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
=======
    private Long createdBy;
>>>>>>> origin/feature/backend-core

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getToolId() {
        return toolId;
    }

    public void setToolId(Long toolId) {
        this.toolId = toolId;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

<<<<<<< HEAD
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
=======
    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
>>>>>>> origin/feature/backend-core
    }
}
