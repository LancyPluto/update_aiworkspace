package com.aiminilab.aitoolmarket.ppt.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PptWorkflow {

    private String integrationMode;
    private String customUiRoute;
    private List<String> creationTypes;
    private List<PptWorkflowStep> steps;
    private Map<String, Boolean> features = new LinkedHashMap<>();

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
}
