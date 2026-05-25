package com.aiminilab.aitoolmarket.tool.integration;

import java.util.regex.Pattern;

/**
 * 平台级集成配置常量。
 *
 * <p>统一以 {@code <!-- tool-integration:{JSON} -->} 形式写入
 * {@code ai_tools.config_note}，详见
 * {@code docs/自定义工作台工具平台化接入规范.md}。</p>
 */
public final class ToolIntegrationConstants {

    public static final String MARKER_PREFIX = "<!-- tool-integration:";
    public static final String MARKER_SUFFIX = " -->";

    public static final Pattern MARKER_PATTERN = Pattern.compile(
            "<!--\\s*tool-integration:(\\{.*?})\\s*-->",
            Pattern.DOTALL
    );

    private ToolIntegrationConstants() {
    }
}
