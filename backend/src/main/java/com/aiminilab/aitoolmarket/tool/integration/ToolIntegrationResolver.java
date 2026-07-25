package com.aiminilab.aitoolmarket.tool.integration;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@link AiTool#getConfigNote() config_note} 解析为
 * {@link ToolIntegrationConfig}。
 *
 * <p>解析顺序：</p>
 * <ol>
 *     <li>优先匹配 {@link ToolIntegrationConstants#MARKER_PATTERN}。</li>
 *     <li>否则匹配旧版 {@code <!-- ppt-workflow:{...} -->}，自动推导
 *     {@code PPT_WORKSPACE} 集成配置以保持向后兼容。</li>
 *     <li>仍未匹配则返回 {@code STANDARD_TASK} 缺省配置。</li>
 * </ol>
 */
@Component
public class ToolIntegrationResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolIntegrationResolver.class);

    /** 旧版 PPT 配置块；保留兼容，未来全部迁到 tool-integration 后可删除。 */
    private static final Pattern LEGACY_PPT_PATTERN = Pattern.compile(
            "<!--\\s*ppt-workflow:(\\{.*?})\\s*-->",
            Pattern.DOTALL
    );

    private static final String DEFAULT_PPT_PLUGIN_ID = "ppt";
    private static final String DEFAULT_PPT_API_PREFIX = "/api/v1/ppt";

    private final ObjectMapper objectMapper;

    public ToolIntegrationResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析工具的集成配置。
     *
     * @return 永远非空；标准任务工具返回的对象 {@link ToolIntegrationConfig#isStandardTask()} 为 true。
     */
    public ToolIntegrationConfig resolve(AiTool tool) {
        if (tool == null) {
            return defaultStandard();
        }
        return resolve(tool.getToolCode(), tool.getConfigNote());
    }

    public ToolIntegrationConfig resolve(String toolCode, String configNote) {
        if (configNote == null || configNote.isBlank()) {
            return defaultStandard();
        }

        Matcher matcher = ToolIntegrationConstants.MARKER_PATTERN.matcher(configNote);
        if (matcher.find()) {
            try {
                ToolIntegrationConfig config = objectMapper.readValue(
                        matcher.group(1),
                        ToolIntegrationConfig.class
                );
                normalize(config, toolCode);
                return config;
            } catch (Exception exception) {
                log.warn("tool-integration JSON 解析失败 toolCode={}: {}", toolCode, exception.getMessage());
            }
        }

        Optional<ToolIntegrationConfig> legacy = parseLegacyPptWorkflow(toolCode, configNote);
        if (legacy.isPresent()) {
            return legacy.get();
        }

        return defaultStandard();
    }

    private Optional<ToolIntegrationConfig> parseLegacyPptWorkflow(String toolCode, String configNote) {
        Matcher matcher = LEGACY_PPT_PATTERN.matcher(configNote);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            JsonNode pptNode = objectMapper.readTree(matcher.group(1));
            ToolIntegrationConfig config = new ToolIntegrationConfig();
            config.setIntegrationMode(IntegrationMode.PPT_WORKSPACE);
            config.setPluginId(DEFAULT_PPT_PLUGIN_ID);
            config.setApiPrefix(textOrDefault(pptNode, "apiPrefix", DEFAULT_PPT_API_PREFIX));
            config.setCustomUiRoute(textOrNull(pptNode, "customUiRoute"));
            config.putExtra(DEFAULT_PPT_PLUGIN_ID, pptNode);
            normalize(config, toolCode);
            return Optional.of(config);
        } catch (Exception exception) {
            log.warn("ppt-workflow 兼容解析失败 toolCode={}: {}", toolCode, exception.getMessage());
            return Optional.empty();
        }
    }

    private void normalize(ToolIntegrationConfig config, String toolCode) {
        if (config.getIntegrationMode() != null) {
            config.setIntegrationMode(config.getIntegrationMode().trim().toUpperCase(Locale.ROOT));
        }
        if (config.getPluginId() != null) {
            config.setPluginId(config.getPluginId().trim());
            if (config.getPluginId().isEmpty()) {
                config.setPluginId(null);
            }
        }
        if (config.getCustomUiRoute() == null || config.getCustomUiRoute().isBlank()) {
            if (!config.isStandardTask() && toolCode != null && !toolCode.isBlank()) {
                config.setCustomUiRoute("/tools/" + toolCode + "/workspace");
            }
        }
    }

    private ToolIntegrationConfig defaultStandard() {
        ToolIntegrationConfig config = new ToolIntegrationConfig();
        config.setIntegrationMode(IntegrationMode.STANDARD_TASK);
        return config;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private String textOrDefault(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }
}
