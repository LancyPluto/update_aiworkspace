package com.aiminilab.aitoolmarket.admin.service;

import java.util.Map;

public interface SystemSettingService {
    Map<String, String> settings();

    Map<String, String> updateSettings(Map<String, String> settings);
}
