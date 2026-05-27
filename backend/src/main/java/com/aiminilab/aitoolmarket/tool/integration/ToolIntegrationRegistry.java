package com.aiminilab.aitoolmarket.tool.integration;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 插件注册表：按 {@code integrationMode} 聚合所有 {@link ToolIntegrationPlugin} Bean。
 *
 * <p>新增插件即新增一个 {@code @Component} 实现，自动被 Spring 注入到本注册表；
 * 平台主链路通过 {@link #find(String)} / {@link #require(String)} 选择处理器。</p>
 */
@Component
public class ToolIntegrationRegistry {

    private final Map<String, ToolIntegrationPlugin> byMode;

    public ToolIntegrationRegistry(List<ToolIntegrationPlugin> plugins) {
        Map<String, ToolIntegrationPlugin> map = new LinkedHashMap<>();
        for (ToolIntegrationPlugin plugin : plugins) {
            String mode = normalize(plugin.integrationMode());
            if (mode == null) {
                continue;
            }
            ToolIntegrationPlugin previous = map.put(mode, plugin);
            if (previous != null && previous != plugin) {
                throw new IllegalStateException(
                        "Duplicate ToolIntegrationPlugin for mode " + mode
                                + ": " + previous.getClass().getName()
                                + " vs " + plugin.getClass().getName()
                );
            }
        }
        this.byMode = Map.copyOf(map);
    }

    public Optional<ToolIntegrationPlugin> find(String integrationMode) {
        String key = normalize(integrationMode);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byMode.get(key));
    }

    public ToolIntegrationPlugin require(String integrationMode) {
        return find(integrationMode).orElseThrow(() -> new BusinessException(
                ErrorCode.PARAM_ERROR,
                "未注册的集成模式: " + integrationMode
        ));
    }

    public boolean isCustomWorkspace(String integrationMode) {
        String key = normalize(integrationMode);
        if (key == null || IntegrationMode.STANDARD_TASK.equals(key)) {
            return false;
        }
        return byMode.containsKey(key);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
