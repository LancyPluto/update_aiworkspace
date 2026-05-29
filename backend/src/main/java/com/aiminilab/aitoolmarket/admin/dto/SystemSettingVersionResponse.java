package com.aiminilab.aitoolmarket.admin.dto;

import com.aiminilab.aitoolmarket.admin.entity.SystemSettingVersion;

import java.time.LocalDateTime;

public record SystemSettingVersionResponse(
        Long id,
        String settingKey,
        String settingValue,
        Long operatorId,
        LocalDateTime createdAt
) {
    public static SystemSettingVersionResponse from(SystemSettingVersion version) {
        return new SystemSettingVersionResponse(
                version.getId(),
                version.getSettingKey(),
                version.getSettingValue(),
                version.getOperatorId(),
                version.getCreatedAt()
        );
    }
}
