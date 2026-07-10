package com.aiminilab.aitoolmarket.admin.mapper;

import com.aiminilab.aitoolmarket.admin.entity.AdminOperationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface AdminOperationLogMapper {
    @Insert("""
            INSERT INTO admin_operation_logs (
                admin_id, operation_type, target_type, target_id, content_json, reason, ip_address
            ) VALUES (
                #{adminId}, #{operationType}, #{targetType}, #{targetId}, #{contentJson}, #{reason}, #{ipAddress}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminOperationLog log);
}
