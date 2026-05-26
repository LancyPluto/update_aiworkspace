package com.aiminilab.aitoolmarket.tool.integration;

/**
 * 工具集成模式枚举（字符串常量形式，便于配置 JSON 透明传递）。
 *
 * <p>{@link #STANDARD_TASK} 表示走「动态表单 → ai_tasks → Redis → worker」标准链路；
 * 其余 {@code *_WORKSPACE} 表示走自定义工作台 BFF 链路，由对应
 * {@link ToolIntegrationPlugin} 注册并处理。</p>
 */
public final class IntegrationMode {

    public static final String STANDARD_TASK = "STANDARD_TASK";

    /** PPT 多步项目工作台（首个落地的自定义工作台）。 */
    public static final String PPT_WORKSPACE = "PPT_WORKSPACE";

    private IntegrationMode() {
    }
}
