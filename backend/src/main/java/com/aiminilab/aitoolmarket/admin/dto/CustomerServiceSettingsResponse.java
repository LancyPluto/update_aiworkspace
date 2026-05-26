package com.aiminilab.aitoolmarket.admin.dto;

import java.util.Map;

public record CustomerServiceSettingsResponse(
        boolean enabled,
        String title,
        String description,
        String qrCodeUrl
) {
    public static CustomerServiceSettingsResponse from(Map<String, String> settings) {
        return new CustomerServiceSettingsResponse(
                !"false".equalsIgnoreCase(settings.getOrDefault("customerService.enabled", "true")),
                settings.getOrDefault("customerService.title", "联系客服"),
                settings.getOrDefault("customerService.description", "扫码添加客服，获取使用支持"),
                settings.getOrDefault("customerService.qrCodeUrl", "")
        );
    }
}
