package com.aiminilab.aitoolmarket.ppt.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class PptWorkflow {

    private String integrationMode;
    private String customUiRoute;
    private List<String> creationTypes;
    private List<PptWorkflowStep> steps;
    private Map<String, Boolean> features = new LinkedHashMap<>();
    /** 绑定超市 agent_model_configs，保存工作流时同步到 banana */
    private Long textModelConfigId;
    private Long imageModelConfigId;
    /**
     * 引擎侧第三方 API 凭证（MinerU、百度 OCR 等），key 与 {@code ToolIntegrationApiCatalog} 中
     * {@link com.aiminilab.aitoolmarket.tool.integration.api.ToolEngineApiFieldDefinition#key()} 一致。
     */
    private Map<String, String> engineSecrets = new LinkedHashMap<>();
    /**
     * 引擎字段来源：key 为目录字段名（如 {@code mineru_token}），值为 {@code system} 表示使用
     * {@code system_settings} 中的全局配置。
     */
    private Map<String, String> engineSecretSources = new LinkedHashMap<>();

    public Long getTextModelConfigId() {
        return textModelConfigId;
    }

    public void setTextModelConfigId(Long textModelConfigId) {
        this.textModelConfigId = textModelConfigId;
    }

    public Long getImageModelConfigId() {
        return imageModelConfigId;
    }

    public void setImageModelConfigId(Long imageModelConfigId) {
        this.imageModelConfigId = imageModelConfigId;
    }

    public Map<String, String> getEngineSecrets() {
        return engineSecrets;
    }

    public void setEngineSecrets(Map<String, String> engineSecrets) {
        this.engineSecrets = engineSecrets == null ? new LinkedHashMap<>() : engineSecrets;
    }

    public Map<String, String> getEngineSecretSources() {
        return engineSecretSources;
    }

    public void setEngineSecretSources(Map<String, String> engineSecretSources) {
        this.engineSecretSources = engineSecretSources == null ? new LinkedHashMap<>() : engineSecretSources;
    }

    public String getIntegrationMode() {
        return integrationMode;
    }

    public void setIntegrationMode(String integrationMode) {
        this.integrationMode = integrationMode;
    }

    public String getCustomUiRoute() {
        return customUiRoute;
    }

    public void setCustomUiRoute(String customUiRoute) {
        this.customUiRoute = customUiRoute;
    }

    public List<String> getCreationTypes() {
        return creationTypes;
    }

    public void setCreationTypes(List<String> creationTypes) {
        this.creationTypes = creationTypes;
    }

    public List<PptWorkflowStep> getSteps() {
        return steps;
    }

    public void setSteps(List<PptWorkflowStep> steps) {
        this.steps = steps;
    }

    public Map<String, Boolean> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Boolean> features) {
        this.features = features == null ? new LinkedHashMap<>() : features;
    }

    public PptWorkflow publicView() {
        PptWorkflow view = new PptWorkflow();
        view.setIntegrationMode(integrationMode);
        view.setCustomUiRoute(customUiRoute);
        view.setCreationTypes(creationTypes == null ? null : List.copyOf(creationTypes));
        view.setSteps(steps == null ? null : List.copyOf(steps));
        view.setFeatures(features == null ? null : new LinkedHashMap<>(features));
        view.setTextModelConfigId(textModelConfigId);
        view.setImageModelConfigId(imageModelConfigId);
        return view;
    }
}
