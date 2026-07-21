package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.ModelAccountRoutingPool;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ModelAccountRoutingPoolMapper extends BaseMapper<ModelAccountRoutingPool> {

    @Select("""
            SELECT *
            FROM model_account_routing_pools
            WHERE id = #{id}
            """)
    ModelAccountRoutingPool findById(@Param("id") Long id);

    @Select("""
            SELECT *
            FROM model_account_routing_pools
            WHERE vendor_code = #{vendorCode}
              AND pool_key = #{poolKey}
            LIMIT 1
            """)
    ModelAccountRoutingPool findByVendorCodeAndPoolKey(@Param("vendorCode") String vendorCode,
                                                        @Param("poolKey") String poolKey);

    @Insert("""
            INSERT INTO model_account_routing_pools(vendor_code, pool_name, pool_key, created_at, updated_at)
            VALUES(#{vendorCode}, #{poolName}, #{poolKey}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
              pool_key = VALUES(pool_key),
              updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("vendorCode") String vendorCode,
               @Param("poolName") String poolName,
               @Param("poolKey") String poolKey);
}
