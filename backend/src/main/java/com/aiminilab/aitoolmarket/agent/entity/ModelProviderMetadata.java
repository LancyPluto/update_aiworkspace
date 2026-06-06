package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("model_provider_metadata")
public class ModelProviderMetadata {
    @TableId
    private Long id;
    private String providerCode;
    private String label;
    private String capabilitiesJson;
    private String defaultBaseUrl;
    private String defaultModel;
    private String billingDefault;
    private String providerProtocol;
    private String vendorKind;
    private String upstreamVendor;
    private String testStrategy;
    private Boolean workerReady;
    private Boolean adapterInstalled;
    private String adapterKey;
    private String metadataVersion;
    private String authSchemaJson;
    private String modelParamSchemaJson;
    private String description;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public String getDefaultBaseUrl() { return defaultBaseUrl; }
    public void setDefaultBaseUrl(String defaultBaseUrl) { this.defaultBaseUrl = defaultBaseUrl; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getBillingDefault() { return billingDefault; }
    public void setBillingDefault(String billingDefault) { this.billingDefault = billingDefault; }
    public String getProviderProtocol() { return providerProtocol; }
    public void setProviderProtocol(String providerProtocol) { this.providerProtocol = providerProtocol; }
    public String getVendorKind() { return vendorKind; }
    public void setVendorKind(String vendorKind) { this.vendorKind = vendorKind; }
    public String getUpstreamVendor() { return upstreamVendor; }
    public void setUpstreamVendor(String upstreamVendor) { this.upstreamVendor = upstreamVendor; }
    public String getTestStrategy() { return testStrategy; }
    public void setTestStrategy(String testStrategy) { this.testStrategy = testStrategy; }
    public Boolean getWorkerReady() { return workerReady; }
    public void setWorkerReady(Boolean workerReady) { this.workerReady = workerReady; }
    public Boolean getAdapterInstalled() { return adapterInstalled; }
    public void setAdapterInstalled(Boolean adapterInstalled) { this.adapterInstalled = adapterInstalled; }
    public String getAdapterKey() { return adapterKey; }
    public void setAdapterKey(String adapterKey) { this.adapterKey = adapterKey; }
    public String getMetadataVersion() { return metadataVersion; }
    public void setMetadataVersion(String metadataVersion) { this.metadataVersion = metadataVersion; }
    public String getAuthSchemaJson() { return authSchemaJson; }
    public void setAuthSchemaJson(String authSchemaJson) { this.authSchemaJson = authSchemaJson; }
    public String getModelParamSchemaJson() { return modelParamSchemaJson; }
    public void setModelParamSchemaJson(String modelParamSchemaJson) { this.modelParamSchemaJson = modelParamSchemaJson; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
