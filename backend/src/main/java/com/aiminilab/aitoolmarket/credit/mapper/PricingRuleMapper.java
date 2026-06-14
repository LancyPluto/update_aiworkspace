package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.PricingRule;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.ArrayList;
import java.util.List;

public interface PricingRuleMapper extends BaseMapper<PricingRule> {

    /**
     * Resolve enabled rules for the given model/tool/category scopes, ordered by priority.
     * Null scope ids are skipped.
     */
    default List<PricingRule> findActiveForScopes(Long modelConfigId, Long toolId, Long categoryId) {
        LambdaQueryWrapper<PricingRule> wrapper = new LambdaQueryWrapper<PricingRule>()
                .eq(PricingRule::getEnabled, true)
                .and(inner -> {
                    boolean[] first = {true};
                    if (modelConfigId != null) {
                        inner.nested(q -> q.eq(PricingRule::getScopeType, "MODEL").eq(PricingRule::getScopeRef, modelConfigId));
                        first[0] = false;
                    }
                    if (toolId != null) {
                        if (!first[0]) {
                            inner.or();
                        }
                        inner.nested(q -> q.eq(PricingRule::getScopeType, "TOOL").eq(PricingRule::getScopeRef, toolId));
                        first[0] = false;
                    }
                    if (categoryId != null) {
                        if (!first[0]) {
                            inner.or();
                        }
                        inner.nested(q -> q.eq(PricingRule::getScopeType, "CATEGORY").eq(PricingRule::getScopeRef, categoryId));
                        first[0] = false;
                    }
                    if (first[0]) {
                        // No scope ids supplied -> match nothing.
                        inner.eq(PricingRule::getId, -1L);
                    }
                })
                .orderByAsc(PricingRule::getPriority)
                .orderByAsc(PricingRule::getId);
        if (modelConfigId == null && toolId == null && categoryId == null) {
            return new ArrayList<>();
        }
        return selectList(wrapper);
    }

    default List<PricingRule> findAllOrdered() {
        return selectList(new LambdaQueryWrapper<PricingRule>()
                .orderByAsc(PricingRule::getScopeType)
                .orderByAsc(PricingRule::getScopeRef)
                .orderByAsc(PricingRule::getPriority)
                .orderByAsc(PricingRule::getId));
    }

    default List<PricingRule> findByModelScope(long modelConfigId) {
        return selectList(new LambdaQueryWrapper<PricingRule>()
                .eq(PricingRule::getScopeType, "MODEL")
                .eq(PricingRule::getScopeRef, modelConfigId)
                .orderByAsc(PricingRule::getPriority)
                .orderByAsc(PricingRule::getId));
    }

    default void deleteByModelScope(long modelConfigId) {
        delete(new LambdaQueryWrapper<PricingRule>()
                .eq(PricingRule::getScopeType, "MODEL")
                .eq(PricingRule::getScopeRef, modelConfigId));
    }
}
