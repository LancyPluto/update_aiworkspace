package com.aiminilab.aitoolmarket.admin.engine;

import com.aiminilab.aitoolmarket.tool.integration.api.ToolApiFieldType;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolEngineApiFieldDefinition;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolIntegrationApiCatalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统级引擎 API（MinerU、百度 OCR 等）在 {@code system_settings} 中的键名。
 */
public final class EngineApiSettingKeys {

    public static final String MINERU_API_BASE = "engine.mineru.apiBase";
    public static final String MINERU_TOKEN = "engine.mineru.token";
    public static final String BAIDU_API_KEY = "engine.baidu.apiKey";

    private static final Map<String, String> FIELD_KEY_TO_SETTING = Map.of(
            "mineru_api_base", MINERU_API_BASE,
            "mineru_token", MINERU_TOKEN,
            "baidu_api_key", BAIDU_API_KEY
    );

    private EngineApiSettingKeys() {
    }

    public static String settingKeyForField(String catalogFieldKey) {
        return FIELD_KEY_TO_SETTING.get(catalogFieldKey);
    }

    public static Map<String, String> catalogKeyToSettingKey() {
        return FIELD_KEY_TO_SETTING;
    }

    public static Map<String, ToolApiFieldType> pptFieldTypes() {
        Map<String, ToolApiFieldType> types = new LinkedHashMap<>();
        ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN)
                .engineApiFields()
                .forEach(field -> types.put(field.key(), field.fieldType()));
        return types;
    }

    public static ToolEngineApiFieldDefinition pptField(String catalogFieldKey) {
        return ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN)
                .engineApiFields()
                .stream()
                .filter(f -> f.key().equals(catalogFieldKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知引擎字段: " + catalogFieldKey));
    }
}
