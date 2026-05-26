package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.ppt.dto.ToolEngineSecretFieldView;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolApiFieldType;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolEngineApiFieldDefinition;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolIntegrationApiCatalog;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolIntegrationApiPluginDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PptEngineSecretSupport {

    private PptEngineSecretSupport() {
    }

    static Map<String, String> normalizeSecrets(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        ToolIntegrationApiPluginDefinition catalog = ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN);
        for (ToolEngineApiFieldDefinition field : catalog.engineApiFields()) {
            String value = raw.get(field.key());
            if (value == null) {
                value = raw.get(field.enginePayloadKey());
            }
            if (value != null && !value.isBlank()) {
                normalized.put(field.enginePayloadKey(), value.trim());
            }
        }
        return normalized;
    }

    static List<ToolEngineSecretFieldView> toViews(Map<String, String> secrets) {
        Map<String, String> safe = secrets == null ? Map.of() : secrets;
        ToolIntegrationApiPluginDefinition catalog = ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN);
        List<ToolEngineSecretFieldView> views = new ArrayList<>();
        for (ToolEngineApiFieldDefinition field : catalog.engineApiFields()) {
            String value = safe.get(field.enginePayloadKey());
            boolean configured = value != null && !value.isBlank();
            views.add(new ToolEngineSecretFieldView(
                    field.key(),
                    configured ? maskForDisplay(value, field.fieldType()) : "",
                    configured
            ));
        }
        return views;
    }

    /**
     * 保存时：密钥类字段留空则保留 workflow 中已有值（避免管理端掩码展示导致误清空）。
     */
    static Map<String, String> mergeIncomingSecrets(Map<String, String> incoming, Map<String, String> existing) {
        Map<String, String> base = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        if (incoming == null || incoming.isEmpty()) {
            return PptEngineSecretSupport.normalizeSecrets(base);
        }
        ToolIntegrationApiPluginDefinition catalog = ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN);
        Map<String, String> result = new LinkedHashMap<>(base);
        for (ToolEngineApiFieldDefinition field : catalog.engineApiFields()) {
            String value = incoming.get(field.key());
            if (value == null) {
                value = incoming.get(field.enginePayloadKey());
            }
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty() && field.fieldType() == ToolApiFieldType.SECRET) {
                continue;
            }
            if (!trimmed.isEmpty()) {
                result.put(field.enginePayloadKey(), trimmed);
            } else {
                result.remove(field.enginePayloadKey());
            }
        }
        return normalizeSecrets(result);
    }

    static void mergeEngineSecretsIntoPayload(Map<String, Object> payload, Map<String, String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return;
        }
        ToolIntegrationApiPluginDefinition catalog = ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN);
        for (ToolEngineApiFieldDefinition field : catalog.engineApiFields()) {
            String value = secrets.get(field.enginePayloadKey());
            if (value != null && !value.isBlank()) {
                payload.put(field.enginePayloadKey(), value.trim());
            }
        }
    }

    private static String maskForDisplay(String value, ToolApiFieldType type) {
        if (type != ToolApiFieldType.SECRET) {
            return value;
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
