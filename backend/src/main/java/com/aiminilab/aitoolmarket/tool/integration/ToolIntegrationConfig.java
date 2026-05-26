package com.aiminilab.aitoolmarket.tool.integration;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具平台级集成配置。
 *
 * <p>对应 {@code config_note} 中的 {@code <!-- tool-integration:{...} -->} JSON。
 * 平台层只关心 {@link #getIntegrationMode()} 等顶层字段；
 * 各插件私有配置以 {@code pluginId} 为键放入 {@link #getExtras() extras}，
 * 由对应 {@link ToolIntegrationPlugin} 自行解析。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolIntegrationConfig {

    private String integrationMode;
    private String pluginId;
    private String customUiRoute;
    private String apiPrefix;
    private String displayName;
    private Engine engine;

    private final Map<String, JsonNode> extras = new LinkedHashMap<>();

    public String getIntegrationMode() {
        return integrationMode;
    }

    public void setIntegrationMode(String integrationMode) {
        this.integrationMode = integrationMode;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getCustomUiRoute() {
        return customUiRoute;
    }

    public void setCustomUiRoute(String customUiRoute) {
        this.customUiRoute = customUiRoute;
    }

    public String getApiPrefix() {
        return apiPrefix;
    }

    public void setApiPrefix(String apiPrefix) {
        this.apiPrefix = apiPrefix;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }

    @JsonAnyGetter
    public Map<String, JsonNode> getExtras() {
        return extras;
    }

    @JsonAnySetter
    public void putExtra(String key, JsonNode value) {
        if (key == null || value == null) {
            return;
        }
        extras.put(key, value);
    }

    public JsonNode extra(String key) {
        return extras.get(key);
    }

    /**
     * 是否为标准任务工具（缺省即标准）。
     */
    public boolean isStandardTask() {
        return integrationMode == null
                || integrationMode.isBlank()
                || IntegrationMode.STANDARD_TASK.equalsIgnoreCase(integrationMode);
    }

    /**
     * 引擎配置；插件按需读取，平台层不强制。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Engine {
        private String type;
        private String baseUrl;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
