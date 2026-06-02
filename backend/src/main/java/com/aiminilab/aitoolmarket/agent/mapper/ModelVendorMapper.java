package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendor;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ModelVendorMapper extends BaseMapper<ModelVendor> {

    @Select("""
            SELECT *
            FROM model_vendors
            WHERE enabled = 1
            ORDER BY sort_order ASC, vendor_code ASC
            """)
    List<ModelVendor> findAllEnabled();

    @Select("""
            SELECT *
            FROM model_vendors
            WHERE vendor_code = #{vendorCode}
            LIMIT 1
            """)
    ModelVendor findByCode(@Param("vendorCode") String vendorCode);
}

