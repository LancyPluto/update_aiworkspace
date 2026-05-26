package com.aiminilab.aitoolmarket.admin.mapper;

import com.aiminilab.aitoolmarket.admin.entity.SystemSetting;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SystemSettingMapper extends BaseMapper<SystemSetting> {

    @Update("""
            CREATE TABLE IF NOT EXISTS system_settings (
              setting_key VARCHAR(128) PRIMARY KEY,
              setting_value TEXT,
              setting_group VARCHAR(64) NOT NULL DEFAULT 'system',
              description VARCHAR(255),
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """)
    void ensureTable();

    @Update("""
            INSERT INTO system_settings (setting_key, setting_value, setting_group)
            VALUES (#{settingKey}, #{settingValue}, 'system')
            ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("settingKey") String settingKey, @Param("settingValue") String settingValue);

    @Update("""
            INSERT IGNORE INTO system_settings (setting_key, setting_value, setting_group, description)
            VALUES (#{settingKey}, #{settingValue}, #{settingGroup}, #{description})
            """)
    void insertIfAbsent(@Param("settingKey") String settingKey,
                        @Param("settingValue") String settingValue,
                        @Param("settingGroup") String settingGroup,
                        @Param("description") String description);
}
