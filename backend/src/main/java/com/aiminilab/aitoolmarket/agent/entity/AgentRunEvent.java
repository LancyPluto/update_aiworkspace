package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_run_events")
public class AgentRunEvent {
    @TableId
    private Long id;
    private Long runId;
    private Long userId;
    private String eventType;
    private String eventText;
    private String eventJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getEventText() { return eventText; }
    public void setEventText(String eventText) { this.eventText = eventText; }
    public String getEventJson() { return eventJson; }
    public void setEventJson(String eventJson) { this.eventJson = eventJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
