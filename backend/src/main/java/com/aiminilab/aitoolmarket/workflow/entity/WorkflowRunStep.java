package com.aiminilab.aitoolmarket.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("workflow_run_steps")
public class WorkflowRunStep {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String nodeId;
    private Integer sequenceNo;
    private String nodeDefType;
    private String status;
    private Long revision;
    private Long taskId;
    private Integer attempt;
    private Integer attemptCount;
    private Long currentAttemptId;
    private Integer maxAttempts;
    private String inputJson;
    private String outputJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public Integer getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }
    public String getNodeDefType() { return nodeDefType; }
    public void setNodeDefType(String nodeDefType) { this.nodeDefType = nodeDefType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Long getCurrentAttemptId() { return currentAttemptId; }
    public void setCurrentAttemptId(Long currentAttemptId) { this.currentAttemptId = currentAttemptId; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String outputJson) { this.outputJson = outputJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
