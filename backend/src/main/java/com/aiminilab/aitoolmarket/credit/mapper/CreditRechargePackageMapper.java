package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargePackage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CreditRechargePackageMapper extends BaseMapper<CreditRechargePackage> {

    @Select("""
            SELECT *
            FROM credit_recharge_packages
            WHERE status = 'ACTIVE'
            ORDER BY recommended DESC, sort_order ASC, id ASC
            """)
    List<CreditRechargePackage> findActive();
}
