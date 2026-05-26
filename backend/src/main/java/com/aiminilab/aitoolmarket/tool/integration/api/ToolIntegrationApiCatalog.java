package com.aiminilab.aitoolmarket.tool.integration.api;

import com.aiminilab.aitoolmarket.ppt.integration.PptIntegrationPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 各工作台插件在管理端「工具编辑 → 用到的 API」中展示的字段目录。
 * 新增插件时在此注册 {@link #register}，前端通过 API 拉取同一份定义。
 */
public final class ToolIntegrationApiCatalog {

    public static final String PPT_PLUGIN = PptIntegrationPlugin.PLUGIN_ID;

    private static final Map<String, ToolIntegrationApiPluginDefinition> BY_PLUGIN = Map.of(
            PPT_PLUGIN, pptDefinition()
    );

    private ToolIntegrationApiCatalog() {
    }

    public static Optional<ToolIntegrationApiPluginDefinition> find(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_PLUGIN.get(pluginId.trim().toLowerCase(Locale.ROOT)));
    }

    public static ToolIntegrationApiPluginDefinition require(String pluginId) {
        return find(pluginId).orElseThrow(() -> new IllegalArgumentException("未知插件 API 目录: " + pluginId));
    }

    private static ToolIntegrationApiPluginDefinition pptDefinition() {
        return new ToolIntegrationApiPluginDefinition(
                PPT_PLUGIN,
                "banana-slides PPT 工作台",
                "配置同步至 PPT 引擎 PUT /api/settings",
                List.of(
                        new ToolModelBindingDefinition(
                                "textModelConfigId",
                                "文本大模型",
                                "大纲、描述等文本生成",
                                "TEXT_GENERATION",
                                "POST /v1/chat/completions 或兼容端点"
                        ),
                        new ToolModelBindingDefinition(
                                "imageModelConfigId",
                                "文生图模型",
                                "幻灯片配图",
                                "IMAGE_GENERATION",
                                "POST /v1/images/generations 或 Ark 图像接口"
                        )
                ),
                List.of(
                        new ToolEngineApiFieldDefinition(
                                "mineru_api_base",
                                "MinerU 服务地址",
                                "PDF / Office 版式解析服务基地址",
                                ToolApiFieldType.URL,
                                "mineru_api_base",
                                "MinerU 上传与解析协议",
                                "https://github.com/opendatalab/MinerU"
                        ),
                        new ToolEngineApiFieldDefinition(
                                "mineru_token",
                                "MinerU API Token",
                                "文档解析、翻新按页抽取等",
                                ToolApiFieldType.SECRET,
                                "mineru_token",
                                null,
                                "https://github.com/opendatalab/MinerU"
                        ),
                        new ToolEngineApiFieldDefinition(
                                "baidu_api_key",
                                "百度通用文字识别（高精度）",
                                "可编辑 PPTX 导出、样式与文字区域提取等",
                                ToolApiFieldType.SECRET,
                                "baidu_api_key",
                                "POST https://aip.baidubce.com/rest/2.0/ocr/v1/accurate",
                                "https://ai.baidu.com/ai-doc/OCR/1k3h7y3db"
                        )
                )
        );
    }
}
