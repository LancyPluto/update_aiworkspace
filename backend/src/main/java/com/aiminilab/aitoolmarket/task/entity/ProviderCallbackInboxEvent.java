package com.aiminilab.aitoolmarket.task.entity;

import java.time.LocalDateTime;

public class ProviderCallbackInboxEvent {
    private Long id;
    private Long registrationId;
    private String providerCode;
    private Long taskId;
    private String providerTaskId;
    private String callbackType;
    private Integer providerStatusCode;
    private String payloadJson;
    private String payloadSha256;
    private String processStatus;
    private LocalDateTime receivedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getProviderTaskId() { return providerTaskId; }
    public void setProviderTaskId(String providerTaskId) { this.providerTaskId = providerTaskId; }
    public String getCallbackType() { return callbackType; }
    public void setCallbackType(String callbackType) { this.callbackType = callbackType; }
    public Integer getProviderStatusCode() { return providerStatusCode; }
    public void setProviderStatusCode(Integer providerStatusCode) { this.providerStatusCode = providerStatusCode; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String payloadSha256) { this.payloadSha256 = payloadSha256; }
    public String getProcessStatus() { return processStatus; }
    public void setProcessStatus(String processStatus) { this.processStatus = processStatus; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
