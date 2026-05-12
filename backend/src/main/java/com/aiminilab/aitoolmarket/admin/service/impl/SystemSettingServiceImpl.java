package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.entity.SystemSetting;
import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SystemSettingServiceImpl implements SystemSettingService {

    private final SystemSettingMapper systemSettingMapper;

    public SystemSettingServiceImpl(SystemSettingMapper systemSettingMapper) {
        this.systemSettingMapper = systemSettingMapper;
    }

    @Override
    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        systemSettingMapper.selectList(null).forEach(setting ->
                result.put(setting.getSettingKey(), setting.getSettingValue()));
        return result;
    }

    @Override
    @Transactional
    public Map<String, String> updateSettings(Map<String, String> settings) {
        settings.forEach((key, value) -> systemSettingMapper.upsert(key, value == null ? "" : value));
        return settings();
    }
}
