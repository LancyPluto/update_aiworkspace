package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.PricingMargin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface PricingMarginMapper extends BaseMapper<PricingMargin> {

    default PricingMargin findEnabledByScope(String scopeType, long scopeRef) {
        return selectOne(new LambdaQueryWrapper<PricingMargin>()
                .eq(PricingMargin::getScopeType, scopeType)
                .eq(PricingMargin::getScopeRef, scopeRef)
                .eq(PricingMargin::getEnabled, true)
                .last("LIMIT 1"));
    }

    default PricingMargin findByScope(String scopeType, long scopeRef) {
        return selectOne(new LambdaQueryWrapper<PricingMargin>()
                .eq(PricingMargin::getScopeType, scopeType)
                .eq(PricingMargin::getScopeRef, scopeRef)
                .last("LIMIT 1"));
    }

    default List<PricingMargin> findAllOrdered() {
        return selectList(new LambdaQueryWrapper<PricingMargin>()
                .orderByAsc(PricingMargin::getScopeType)
                .orderByAsc(PricingMargin::getScopeRef));
    }
}
