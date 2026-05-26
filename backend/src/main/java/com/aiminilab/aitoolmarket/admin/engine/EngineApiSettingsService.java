package com.aiminilab.aitoolmarket.admin.engine;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.ppt.dto.ToolEngineSecretFieldView;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolApiFieldType;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolEngineApiFieldDefinition;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolIntegrationApiCatalog;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolIntegrationApiPluginDefinition;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EngineApiSettingsService {

    public static final String SOURCE_SYSTEM = "system";

    private final SystemSettingService systemSettingService;

    public EngineApiSettingsService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    public Map<String, String> settingsForAdmin() {
        Map<String, String> all = systemSettingService.settings();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : EngineApiSettingKeys.catalogKeyToSettingKey().entrySet()) {
            String settingKey = entry.getValue();
            String raw = all.get(settingKey);
            ToolApiFieldType type = EngineApiSettingKeys.pptFieldTypes().get(entry.getKey());
            if (type == ToolApiFieldType.SECRET) {
                result.put(settingKey, maskSecret(raw));
            } else {
                result.put(settingKey, raw == null ? "" : raw);
            }
        }
        return result;
    }

    public Map<String, String> updateSettings(Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return settingsForAdmin();
        }
        Map<String, String> existing = systemSettingService.settings();
        Map<String, String> toSave = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : EngineApiSettingKeys.catalogKeyToSettingKey().entrySet()) {
            String catalogKey = entry.getKey();
            String settingKey = entry.getValue();
            if (!incoming.containsKey(settingKey)) {
                continue;
            }
            String value = incoming.get(settingKey);
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            ToolApiFieldType type = EngineApiSettingKeys.pptFieldTypes().get(catalogKey);
            if (trimmed.isEmpty() && type == ToolApiFieldType.SECRET) {
                String previous = existing.get(settingKey);
                if (previous != null && !previous.isBlank()) {
                    continue;
                }
            }
            toSave.put(settingKey, trimmed);
        }
        if (!toSave.isEmpty()) {
            systemSettingService.updateSettings(toSave);
        }
        return settingsForAdmin();
    }

    public List<ToolEngineSecretFieldView> viewsForPptCatalog() {
        return viewsForPlugin(ToolIntegrationApiCatalog.PPT_PLUGIN);
    }

    public List<ToolEngineSecretFieldView> viewsForPlugin(String pluginId) {
        ToolIntegrationApiPluginDefinition catalog = ToolIntegrationApiCatalog.require(pluginId);
        Map<String, String> resolved = resolveRawSecrets(catalog);
        List<ToolEngineSecretFieldView> views = new java.util.ArrayList<>();
        for (ToolEngineApiFieldDefinition field : catalog.engineApiFields()) {
            String value = resolved.get(field.enginePayloadKey());
            boolean configured = value != null && !value.isBlank();
            views.add(new ToolEngineSecretFieldView(
                    field.key(),
                    configured ? displayValue(value, field.fieldType()) : "",
                    configured
            ));
        }
        return views;
    }

    public Map<String, String> resolveRawSecrets(ToolIntegrationApiPluginDefinition catalog) {
        Map<String, String> all = systemSettingService.settings();
        Map<String, String> result = new LinkedHashMap<>();
        for (ToolEngineApiFieldDefinition field : catalog.engineApiFields()) {
            String settingKey = EngineApiSettingKeys.settingKeyForField(field.key());
            if (settingKey == null) {
                continue;
            }
            String value = all.get(settingKey);
            if (value != null && !value.isBlank()) {
                result.put(field.enginePayloadKey(), value.trim());
            }
        }
        return result;
    }

    /**
     * 合并工具 workflow 中的引擎密钥与「使用系统配置」标记。
     */
    public Map<String, String> resolveForWorkflow(Map<String, String> workflowSecrets,
                                                  Map<String, String> secretSources) {
        ToolIntegrationApiPluginDefinition catalog = ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN);
        Map<String, String> system = resolveRawSecrets(catalog);
        Map<String, String> tool = workflowSecrets == null ? Map.of() : workflowSecrets;
        Map<String, String> sources = secretSources == null ? Map.of() : secretSources;
        Map<String, String> merged = new LinkedHashMap<>();
        for (ToolEngineApiFieldDefinition field : catalog.engineApiFields()) {
            String payloadKey = field.enginePayloadKey();
            boolean useSystem = SOURCE_SYSTEM.equalsIgnoreCase(sources.getOrDefault(field.key(), "").trim());
            String value = null;
            if (useSystem) {
                value = system.get(payloadKey);
            } else {
                value = tool.get(payloadKey);
                if (value == null) {
                    value = tool.get(field.key());
                }
            }
            if (value != null && !value.isBlank()) {
                merged.put(payloadKey, value.trim());
            }
        }
        return merged;
    }

    private static String displayValue(String value, ToolApiFieldType type) {
        if (type != ToolApiFieldType.SECRET) {
            return value;
        }
        return maskSecret(value);
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
