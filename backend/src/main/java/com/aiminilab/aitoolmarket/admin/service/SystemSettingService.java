package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.CustomerServiceQrUploadResponse;
import com.aiminilab.aitoolmarket.admin.dto.SystemSettingVersionResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface SystemSettingService {
    Map<String, String> settings();

    Map<String, String> updateSettings(Map<String, String> settings);

    default Map<String, String> updateSettings(Map<String, String> settings, Long operatorId) {
        return updateSettings(settings);
    }

    default List<SystemSettingVersionResponse> settingVersions(String key) {
        return List.of();
    }

    default Map<String, String> restoreDefault(String key, Long operatorId) {
        return settings();
    }

    CustomerServiceQrUploadResponse uploadCustomerServiceQr(MultipartFile file);
}
