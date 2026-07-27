package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendor;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class VendorCodeResolver {

    private static final String DEPRECATED_GATEWAY_VENDOR = "openai_gateway";
    private static final String ACCOUNT_VENDOR_DRIVEN_PROVIDER = "openai_images_gateway";

    private static final Map<String, String> PROVIDER_TO_VENDOR = Map.ofEntries(
            Map.entry("deepseek", "deepseek"),
            Map.entry("openai_compatible", "openai"),
            Map.entry("moonshot_compatible", "moonshot"),
            Map.entry("anthropic_compatible", "minimax"),
            Map.entry("minimax", "minimax"),
            Map.entry("minimax_speech", "minimax"),
            Map.entry("minimax_music", "minimax"),
            Map.entry("suno_music", "suno"),
            Map.entry("siliconflow_images", "siliconflow"),
            Map.entry("siliconflow_speech", "siliconflow"),
            Map.entry("siliconflow_asr", "siliconflow"),
            Map.entry("volcengine_images", "volcengine"),
            Map.entry("seedance", "volcengine"),
            Map.entry("infinitetalk", "infinitetalk"),
            Map.entry("kling_video", "kling"),
            Map.entry("bailian_happyhorse", "qwen"),
            Map.entry("dashscope_qwen_tts", "qwen"),
            Map.entry("ofox_openai_images", "ofox"),
            Map.entry("agnes_chat", "agnes"),
            Map.entry("agnes_images", "agnes"),
            Map.entry("agnes_video", "agnes"),
            Map.entry("vidu_async", "vidu"),
            Map.entry("worker_video", "siliconflow"),
            Map.entry("mineru", "mineru"),
            Map.entry("mock", "mock")
    );

    private static final Map<String, String> VENDOR_LABELS = new LinkedHashMap<>();

    static {
        VENDOR_LABELS.put("deepseek", "DeepSeek");
        VENDOR_LABELS.put("openai", "OpenAI");
        VENDOR_LABELS.put("ofox", "oFox");
        VENDOR_LABELS.put("agnes", "Agnes AI");
        VENDOR_LABELS.put("google", "Google Gemini");
        VENDOR_LABELS.put("qwen", "阿里云百炼");
        VENDOR_LABELS.put("zhipu", "智谱 GLM");
        VENDOR_LABELS.put("moonshot", "Moonshot / Kimi");
        VENDOR_LABELS.put("anthropic", "Anthropic Claude");
        VENDOR_LABELS.put("minimax", "MiniMax");
        VENDOR_LABELS.put("suno", "Suno");
        VENDOR_LABELS.put("siliconflow", "SiliconFlow");
        VENDOR_LABELS.put("volcengine", "火山引擎 / 豆包");
        VENDOR_LABELS.put("kling", "可灵");
        VENDOR_LABELS.put("vidu", "Vidu (生数科技)");
        VENDOR_LABELS.put("mineru", "MinerU");
        VENDOR_LABELS.put("infinitetalk", "InfiniteTalk");
        VENDOR_LABELS.put("mock", "Mock");
    }

    private final ModelProviderRegistry providerRegistry;
    private final ModelVendorMapper modelVendorMapper;

    public VendorCodeResolver(ModelProviderRegistry providerRegistry, ModelVendorMapper modelVendorMapper) {
        this.providerRegistry = providerRegistry;
        this.modelVendorMapper = modelVendorMapper;
    }

    public String resolveVendorCode(AgentModelConfig config) {
        return resolveVendorCode(config.getProvider(), config.getBaseUrl(), config.getDisplayName(), config.getModelName());
    }

    public String resolveEffectiveVendorCode(ModelVendorAccount account) {
        if (account == null) {
            return "other";
        }
        String declared = canonicalVendorCode(account.getVendorCode());
        String inferred = canonicalVendorCode(inferStrictVendorFromBaseUrl(account.getBaseUrl()));
        if ((declared.isBlank() || "openai".equals(declared))
                && !inferred.isBlank()
                && !"openai".equals(inferred)
                && !"other".equals(inferred)) {
            return inferred;
        }
        return declared.isBlank() ? (inferred.isBlank() ? "other" : inferred) : declared;
    }

    public String resolveVendorCode(String provider, String baseUrl, String displayName, String modelName) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (ACCOUNT_VENDOR_DRIVEN_PROVIDER.equals(normalizedProvider)) {
            String inferred = inferGatewayVendorFromBaseUrl(baseUrl);
            return inferred == null ? "other" : inferred;
        }
        String mapped = PROVIDER_TO_VENDOR.get(normalizedProvider);
        if ("openai_compatible".equals(normalizedProvider)) {
            return inferFromText(baseUrl, displayName, modelName, "openai");
        }
        if ("anthropic_compatible".equals(normalizedProvider)) {
            return inferFromText(baseUrl, displayName, modelName, mapped == null ? "minimax" : mapped);
        }
        if (mapped != null && !"openai".equals(mapped)) {
            return mapped;
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

    public String canonicalVendorCode(String vendorCode) {
        String normalized = vendorCode == null ? "" : vendorCode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "bailian_happyhorse", "dashscope", "aliyun_bailian" -> "qwen";
            case "kimi", "moonshot_compatible" -> "moonshot";
            case "suno_music" -> "suno";
            default -> normalized;
        };
    }

    public String requireConcreteVendorCode(String vendorCode) {
        String normalized = canonicalVendorCode(vendorCode);
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendorCode is required");
        }
        if (DEPRECATED_GATEWAY_VENDOR.equals(normalized)) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "openai_gateway is a compatibility channel, not a vendor; select the actual credential issuer"
            );
        }
        return normalized;
    }

    public boolean usesBoundAccountVendor(String providerCode) {
        return providerCode != null
                && ACCOUNT_VENDOR_DRIVEN_PROVIDER.equals(providerCode.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isDeprecatedVirtualVendorCode(String vendorCode) {
        return DEPRECATED_GATEWAY_VENDOR.equals(canonicalVendorCode(vendorCode));
    }

    public String resolvePublicVendorCode(AgentModelConfig config) {
        if (config == null) {
            return "other";
        }
        String upstreamVendor = providerRegistry.findByCode(config.getProvider())
                .map(ModelProviderDefinition::upstreamVendor)
                .orElse(null);
        if (upstreamVendor != null && !upstreamVendor.isBlank()) {
            return canonicalVendorCode(upstreamVendor);
        }
        return canonicalVendorCode(resolveVendorCode(config));
    }

    public String vendorLabel(String vendorCode) {
        if (vendorCode == null || vendorCode.isBlank()) {
            return "其他";
        }
        ModelVendor vendor = findVendorByCode(vendorCode);
        if (vendor != null && Boolean.TRUE.equals(vendor.getEnabled())
                && vendor.getVendorLabel() != null && !vendor.getVendorLabel().isBlank()) {
            return vendor.getVendorLabel();
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
        LinkedHashMap<String, String> catalog = new LinkedHashMap<>();
        for (ModelVendor vendor : findAllEnabledVendors()) {
            if (vendor.getVendorCode() == null || vendor.getVendorCode().isBlank()) {
                continue;
            }
            if (DEPRECATED_GATEWAY_VENDOR.equals(canonicalVendorCode(vendor.getVendorCode()))) {
                continue;
            }
            catalog.putIfAbsent(vendor.getVendorCode(), vendorLabel(vendor.getVendorCode()));
        }
        catalog.putAll(VENDOR_LABELS);
        for (String code : allKnownVendorCodes()) {
            catalog.putIfAbsent(code, vendorLabel(code));
        }
        return catalog;
    }

    public String vendorIconAsset(String vendorCode) {
        if (vendorCode == null || vendorCode.isBlank()) {
            return "api";
        }
        ModelVendor vendor = findVendorByCode(vendorCode);
        if (vendor != null && Boolean.TRUE.equals(vendor.getEnabled())
                && vendor.getIconAsset() != null && !vendor.getIconAsset().isBlank()) {
            return vendor.getIconAsset();
        }
        if ("volcengine".equalsIgnoreCase(vendorCode)) {
            return "doubao";
        }
        if ("google".equalsIgnoreCase(vendorCode)) {
            return "gemini";
        }
        return vendorCode;
    }

    private ModelVendor findVendorByCode(String vendorCode) {
        try {
            return modelVendorMapper.findByCode(vendorCode);
        } catch (DataAccessException exception) {
            return null;
        }
    }

    private java.util.List<ModelVendor> findAllEnabledVendors() {
        try {
            return modelVendorMapper.findAllEnabled();
        } catch (DataAccessException exception) {
            return java.util.List.of();
        }
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
        if (text.contains("sunoapi.org") || text.contains("suno.com") || text.contains("suno")) return "suno";
        if (text.contains("generativelanguage.googleapis.com") || text.contains("googleapis.com")) return "google";
        if (text.contains("dashscope.aliyuncs.com") || text.contains("aliyuncs.com")) return "qwen";
        if (text.contains("bigmodel.cn") || text.contains("zhipu")) return "zhipu";
        if (text.contains("moonshot.cn") || text.contains("kimi")) return "moonshot";
        if (text.contains("anthropic.com") || text.contains("claude")) return "anthropic";
        if (text.contains("agnes-ai.com") || text.contains("apihub.agnes-ai.com")) return "agnes";
        if (text.contains("mineru.net") || text.contains("mineru")) return "mineru";
        if (text.contains("openai.com")) return "openai";
        if (text.contains("api.openai.com")) return "openai";
        if (text.contains("ofox.ai")) return "ofox";
        return null;
    }

    private static String inferGatewayVendorFromBaseUrl(String baseUrl) {
        String host = host(baseUrl);
        if ("api.openai.com".equals(host)) return "openai";
        if (matchesDomain(host, "ofox.ai")) return "ofox";
        return null;
    }

    private static String inferStrictVendorFromBaseUrl(String baseUrl) {
        String host = host(baseUrl);
        if (host == null) return null;
        if ("api.openai.com".equals(host)) return "openai";
        if (matchesDomain(host, "ofox.ai")) return "ofox";
        if (matchesDomain(host, "moonshot.cn")) return "moonshot";
        if (matchesDomain(host, "deepseek.com")) return "deepseek";
        if (matchesDomain(host, "siliconflow.cn")) return "siliconflow";
        if (matchesDomain(host, "volces.com") || matchesDomain(host, "volcengine.com")) return "volcengine";
        if (matchesDomain(host, "klingai.com")) return "kling";
        if (matchesDomain(host, "minimaxi.com")) return "minimax";
        if (matchesDomain(host, "sunoapi.org") || matchesDomain(host, "suno.com")) return "suno";
        if (matchesDomain(host, "googleapis.com")) return "google";
        if (matchesDomain(host, "aliyuncs.com")) return "qwen";
        if (matchesDomain(host, "bigmodel.cn")) return "zhipu";
        if (matchesDomain(host, "anthropic.com")) return "anthropic";
        if (matchesDomain(host, "agnes-ai.com")) return "agnes";
        if (matchesDomain(host, "mineru.net")) return "mineru";
        if (matchesDomain(host, "vidu.com")) return "vidu";
        return null;
    }

    private static String host(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean matchesDomain(String host, String domain) {
        return host != null && (domain.equals(host) || host.endsWith("." + domain));
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
        if (combined.contains("suno")) return "suno";
        if (combined.contains("gemini") || combined.contains("google")) return "google";
        if (combined.contains("qwen") || combined.contains("tongyi") || combined.contains("通义")) return "qwen";
        if (combined.contains("zhipu") || combined.contains("glm") || combined.contains("chatglm") || combined.contains("智谱")) return "zhipu";
        if (combined.contains("moonshot") || combined.contains("kimi")) return "moonshot";
        if (combined.contains("anthropic") || combined.contains("claude")) return "anthropic";
        if (combined.contains("agnes") || combined.contains("apihub.agnes-ai.com")) return "agnes";
        if (combined.contains("mineru")) return "mineru";
        String fromUrl = inferFromBaseUrl(baseUrl);
        if (fromUrl != null) {
            return fromUrl;
        }
        if (combined.contains("gpt-") || combined.contains("chatgpt")) return "openai";
        return fallback;
    }

    private static String capitalize(String code) {
        if (code == null || code.isBlank()) {
            return "其他";
        }
        return code.substring(0, 1).toUpperCase(Locale.ROOT) + code.substring(1).replace('_', ' ');
    }
}
