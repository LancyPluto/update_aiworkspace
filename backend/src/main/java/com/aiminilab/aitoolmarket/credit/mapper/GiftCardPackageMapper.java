package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.GiftCardPackage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface GiftCardPackageMapper extends BaseMapper<GiftCardPackage> {

    default List<GiftCardPackage> findActive() {
        return selectList(new LambdaQueryWrapper<GiftCardPackage>()
                .eq(GiftCardPackage::getStatus, "ACTIVE")
                .orderByAsc(GiftCardPackage::getSortOrder));
    }

    default GiftCardPackage findActiveById(Long id) {
        return selectOne(new LambdaQueryWrapper<GiftCardPackage>()
                .eq(GiftCardPackage::getId, id)
                .eq(GiftCardPackage::getStatus, "ACTIVE")
                .last("LIMIT 1"));
    }
}
