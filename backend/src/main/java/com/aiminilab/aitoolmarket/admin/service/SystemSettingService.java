package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.CustomerServiceQrUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface SystemSettingService {
    Map<String, String> settings();

    Map<String, String> updateSettings(Map<String, String> settings);

    CustomerServiceQrUploadResponse uploadCustomerServiceQr(MultipartFile file);
}
