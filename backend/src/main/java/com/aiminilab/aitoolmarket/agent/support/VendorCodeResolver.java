package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class VendorCodeResolver {

    private static final Map<String, String> PROVIDER_TO_VENDOR = Map.ofEntries(
            Map.entry("deepseek", "deepseek"),
            Map.entry("openai_compatible", "openai"),
            Map.entry("anthropic_compatible", "minimax"),
            Map.entry("minimax", "minimax"),
            Map.entry("minimax_speech", "minimax"),
            Map.entry("minimax_music", "minimax"),
            Map.entry("siliconflow_images", "siliconflow"),
            Map.entry("siliconflow_speech", "siliconflow"),
            Map.entry("siliconflow_asr", "siliconflow"),
            Map.entry("volcengine_images", "volcengine"),
            Map.entry("seedance", "volcengine"),
            Map.entry("kling_video", "kling"),
            Map.entry("ofox_openai_images", "openai_gateway"),
            Map.entry("openai_images_gateway", "openai_gateway"),
            Map.entry("infinitetalk", "infinitetalk"),
            Map.entry("worker_video", "siliconflow"),
            Map.entry("mock", "mock")
    );

    private static final Map<String, String> VENDOR_LABELS = new LinkedHashMap<>();

    static {
        VENDOR_LABELS.put("deepseek", "DeepSeek");
        VENDOR_LABELS.put("openai", "OpenAI");
        VENDOR_LABELS.put("openai_gateway", "OpenAI 兼容网关");
        VENDOR_LABELS.put("minimax", "MiniMax");
        VENDOR_LABELS.put("siliconflow", "SiliconFlow");
        VENDOR_LABELS.put("volcengine", "火山引擎 / 豆包");
        VENDOR_LABELS.put("kling", "可灵");
        VENDOR_LABELS.put("infinitetalk", "InfiniteTalk");
        VENDOR_LABELS.put("mock", "Mock");
    }

    private final ModelProviderRegistry providerRegistry;

    public VendorCodeResolver(ModelProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public String resolveVendorCode(AgentModelConfig config) {
        return resolveVendorCode(config.getProvider(), config.getBaseUrl(), config.getDisplayName(), config.getModelName());
    }

    public String resolveVendorCode(String provider, String baseUrl, String displayName, String modelName) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String mapped = PROVIDER_TO_VENDOR.get(normalizedProvider);
        if (mapped != null && !"openai".equals(mapped)) {
            return mapped;
        }
        if ("openai_compatible".equals(normalizedProvider)) {
            return inferFromText(baseUrl, displayName, modelName, "openai");
        }
        if ("ofox_openai_images".equals(normalizedProvider) || "openai_images_gateway".equals(normalizedProvider)) {
            return "openai_gateway";
        }
        String fromUrl = inferFromBaseUrl(baseUrl);
        if (fromUrl != null) {
            return fromUrl;
        }
        if (mapped != null) {
            return mapped;
        }
        return normalizedProvider.isBlank() ? "other" : normalizedProvider;
    }

    public String vendorLabel(String vendorCode) {
        if (vendorCode == null || vendorCode.isBlank()) {
            return "其他";
        }
        return VENDOR_LABELS.getOrDefault(vendorCode, capitalize(vendorCode));
    }

    public Set<String> allKnownVendorCodes() {
        LinkedHashMap<String, String> codes = new LinkedHashMap<>(VENDOR_LABELS);
        for (ModelProviderDefinition definition : providerRegistry.listAll()) {
            String vendor = resolveVendorCode(definition.code(), definition.defaultBaseUrl(), definition.label(), definition.defaultModel());
            codes.putIfAbsent(vendor, vendorLabel(vendor));
        }
        return codes.keySet();
    }

    public Map<String, String> vendorCatalog() {
        LinkedHashMap<String, String> catalog = new LinkedHashMap<>(VENDOR_LABELS);
        for (String code : allKnownVendorCodes()) {
            catalog.putIfAbsent(code, vendorLabel(code));
        }
        return catalog;
    }

    private static String inferFromBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String text = baseUrl.toLowerCase(Locale.ROOT);
        if (text.contains("deepseek")) return "deepseek";
        if (text.contains("siliconflow")) return "siliconflow";
        if (text.contains("volces.com") || text.contains("volcengine")) return "volcengine";
        if (text.contains("klingai.com") || text.contains("kling")) return "kling";
        if (text.contains("minimaxi.com") || text.contains("minimax")) return "minimax";
        if (text.contains("openai.com")) return "openai";
        if (text.contains("ofox.ai")) return "openai_gateway";
        return null;
    }

    private static String inferFromText(String baseUrl, String displayName, String modelName, String fallback) {
        String combined = String.join(" ",
                baseUrl == null ? "" : baseUrl,
                displayName == null ? "" : displayName,
                modelName == null ? "" : modelName).toLowerCase(Locale.ROOT);
        if (combined.contains("deepseek")) return "deepseek";
        if (combined.contains("siliconflow")) return "siliconflow";
        if (combined.contains("kling")) return "kling";
        if (combined.contains("doubao") || combined.contains("seed") || combined.contains("volc")) return "volcengine";
        if (combined.contains("minimax")) return "minimax";
        String fromUrl = inferFromBaseUrl(baseUrl);
        return fromUrl != null ? fromUrl : fallback;
    }

    private static String capitalize(String code) {
        if (code == null || code.isBlank()) {
            return "其他";
        }
        return code.substring(0, 1).toUpperCase(Locale.ROOT) + code.substring(1).replace('_', ' ');
    }
}
