package com.aiminilab.aitoolmarket.auth.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface AuthSecurityEventMapper {

    @Insert("""
            INSERT INTO auth_security_events (
              event_type, result, method, user_type, user_id,
              account_hash, account_masked, failure_reason,
              ip_address, user_agent, trace_id,
              country, region, city, latitude, longitude
            ) VALUES (
              #{eventType}, #{result}, #{method}, #{userType}, #{userId},
              #{accountHash}, #{accountMasked}, #{failureReason},
              #{ipAddress}, #{userAgent}, #{traceId},
              #{country}, #{region}, #{city}, #{latitude}, #{longitude}
            )
            """)
    int insert(@Param("eventType") String eventType,
               @Param("result") String result,
               @Param("method") String method,
               @Param("userType") String userType,
               @Param("userId") Long userId,
               @Param("accountHash") String accountHash,
               @Param("accountMasked") String accountMasked,
               @Param("failureReason") String failureReason,
               @Param("ipAddress") String ipAddress,
               @Param("userAgent") String userAgent,
               @Param("traceId") String traceId,
               @Param("country") String country,
               @Param("region") String region,
               @Param("city") String city,
               @Param("latitude") BigDecimal latitude,
               @Param("longitude") BigDecimal longitude);
}
