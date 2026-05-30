package com.aiminilab.aitoolmarket.admin.mapper;

import com.aiminilab.aitoolmarket.admin.entity.SystemSettingVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface SystemSettingVersionMapper extends BaseMapper<SystemSettingVersion> {

    @Update("""
            CREATE TABLE IF NOT EXISTS system_setting_versions (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              setting_key VARCHAR(128) NOT NULL,
              setting_value TEXT,
              operator_id BIGINT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              KEY idx_system_setting_versions_key (setting_key, id)
            )
            """)
    void ensureTable();

    @Insert("""
            INSERT INTO system_setting_versions(setting_key, setting_value, operator_id, created_at)
            VALUES(#{version.settingKey}, #{version.settingValue}, #{version.operatorId}, #{version.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "version.id")
    void insertVersion(@Param("version") SystemSettingVersion version);

    @Select("""
            SELECT *
            FROM system_setting_versions
            WHERE setting_key = #{settingKey}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<SystemSettingVersion> findByKey(@Param("settingKey") String settingKey, @Param("limit") int limit);
}
