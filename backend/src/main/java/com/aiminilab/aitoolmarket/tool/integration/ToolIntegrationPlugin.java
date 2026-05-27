package com.aiminilab.aitoolmarket.tool.integration;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;

/**
 * 自定义工作台插件接口。
 *
 * <p>每种 {@link IntegrationMode#PPT_WORKSPACE 自定义工作台} 由一个
 * {@link org.springframework.stereotype.Component} 实现注册到
 * {@link ToolIntegrationRegistry}；平台主链路（ToolDetail、Admin 入口）只与该接口交互，
 * 不再 {@code switch(toolCode)}。</p>
 */
public interface ToolIntegrationPlugin {

    /** 匹配的 integrationMode，例如 {@link IntegrationMode#PPT_WORKSPACE}。 */
    String integrationMode();

    /** 插件 id，与配置里的 {@code pluginId}、前端 registry key 一致。 */
    String pluginId();

    /**
     * 用户详情接口附带的扩展数据。
     *
     * <p>例如 PPT 插件可返回 {@code PptWorkflow} 供前端渲染步骤、估算算力等；
     * 标准任务不应注册插件，因此这里默认为 {@code null}。</p>
     */
    default Object userDetailExtension(AiTool tool, ToolIntegrationConfig config) {
        return null;
    }
}
