package com.aiminilab.aitoolmarket.ppt.dto;

/**
 * 管理端展示的一项引擎密钥/URL 配置（不回传明文密钥）。
 */
public record ToolEngineSecretFieldView(
        String key,
        String displayValue,
        boolean configured
) {
}
