package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.PricingMarginUpsertRequest;
import com.aiminilab.aitoolmarket.admin.dto.PricingRuleUpsertRequest;
import com.aiminilab.aitoolmarket.admin.service.PricingConfigAdminService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.entity.PricingMargin;
import com.aiminilab.aitoolmarket.credit.entity.PricingRule;
import com.aiminilab.aitoolmarket.credit.mapper.PricingMarginMapper;
import com.aiminilab.aitoolmarket.credit.mapper.PricingRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
public class PricingConfigAdminServiceImpl implements PricingConfigAdminService {

    private static final Set<String> MARGIN_SCOPES = Set.of("GLOBAL", "CATEGORY", "MODEL");
    private static final Set<String> RULE_SCOPES = Set.of("MODEL", "TOOL", "CATEGORY");
    private static final Set<String> RULE_TYPES = Set.of("MULTIPLIER", "TIER", "ADDITIVE");
    private static final Set<String> MATCH_OPS = Set.of("ANY", "EQ", "VALUE", "GT", "GTE", "LT", "LTE");

    private final PricingMarginMapper pricingMarginMapper;
    private final PricingRuleMapper pricingRuleMapper;

    public PricingConfigAdminServiceImpl(PricingMarginMapper pricingMarginMapper, PricingRuleMapper pricingRuleMapper) {
        this.pricingMarginMapper = pricingMarginMapper;
        this.pricingRuleMapper = pricingRuleMapper;
    }

    @Override
    public List<PricingMargin> listMargins() {
        return pricingMarginMapper.findAllOrdered();
    }

    @Override
    @Transactional
    public PricingMargin saveMargin(PricingMarginUpsertRequest request) {
        String scopeType = normalizeScope(request.scopeType(), MARGIN_SCOPES);
        long scopeRef = "GLOBAL".equals(scopeType) ? 0L : requireScopeRef(request.scopeRef());
        BigDecimal markup = request.markupRatio() == null || request.markupRatio().compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("1.20")
                : request.markupRatio();
        int minCredits = request.minCredits() == null ? 0 : Math.max(0, request.minCredits());
        Integer imageEstimateInputTokens = positiveOrNull(request.imageEstimateInputTokens());
        Integer imageEstimateOutputTokens = positiveOrNull(request.imageEstimateOutputTokens());
        Integer tokenEstimateInputTokens = positiveOrNull(request.tokenEstimateInputTokens());
        Integer tokenEstimateOutputTokens = positiveOrNull(request.tokenEstimateOutputTokens());

        PricingMargin entity;
        if (request.id() != null) {
            entity = pricingMarginMapper.selectById(request.id());
            if (entity == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "加价配置不存在");
            }
        } else {
            entity = pricingMarginMapper.findByScope(scopeType, scopeRef);
            if (entity == null) {
                entity = new PricingMargin();
            }
        }
        entity.setScopeType(scopeType);
        entity.setScopeRef(scopeRef);
        entity.setMarkupRatio(markup);
        entity.setMinCredits(minCredits);
        entity.setImageEstimateInputTokens(imageEstimateInputTokens);
        entity.setImageEstimateOutputTokens(imageEstimateOutputTokens);
        entity.setTokenEstimateInputTokens(tokenEstimateInputTokens);
        entity.setTokenEstimateOutputTokens(tokenEstimateOutputTokens);
        entity.setEnabled(request.enabled() == null ? Boolean.TRUE : request.enabled());
        entity.setRemark(request.remark());
        if (entity.getId() == null) {
            pricingMarginMapper.insert(entity);
        } else {
            pricingMarginMapper.updateById(entity);
        }
        return entity;
    }

    @Override
    @Transactional
    public void deleteMargin(Long id) {
        if (id == null) {
            return;
        }
        PricingMargin existing = pricingMarginMapper.selectById(id);
        if (existing != null && "GLOBAL".equals(existing.getScopeType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "全局默认加价不可删除");
        }
        pricingMarginMapper.deleteById(id);
    }

    @Override
    public List<PricingRule> listRules() {
        return pricingRuleMapper.findAllOrdered();
    }

    @Override
    @Transactional
    public PricingRule saveRule(PricingRuleUpsertRequest request) {
        String scopeType = normalizeScope(request.scopeType(), RULE_SCOPES);
        long scopeRef = requireScopeRef(request.scopeRef());
        if (request.paramKey() == null || request.paramKey().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "参数键不能为空");
        }
        String ruleType = normalizeUpper(request.ruleType(), RULE_TYPES, "MULTIPLIER");
        String matchOp = normalizeUpper(request.matchOp(), MATCH_OPS, "EQ");
        BigDecimal factor = request.factor() == null || request.factor().compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ONE
                : request.factor();
        int extraCredits = request.extraCredits() == null ? 0 : Math.max(0, request.extraCredits());

        PricingRule entity = request.id() == null ? new PricingRule() : pricingRuleMapper.selectById(request.id());
        if (entity == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "定价规则不存在");
        }
        entity.setScopeType(scopeType);
        entity.setScopeRef(scopeRef);
        entity.setParamKey(request.paramKey().trim());
        entity.setRuleType(ruleType);
        entity.setMatchOp(matchOp);
        entity.setMatchValue(request.matchValue());
        entity.setFactor(factor);
        entity.setExtraCredits(extraCredits);
        entity.setPriority(request.priority() == null ? 100 : request.priority());
        entity.setEnabled(request.enabled() == null ? Boolean.TRUE : request.enabled());
        entity.setRemark(request.remark());
        if (entity.getId() == null) {
            pricingRuleMapper.insert(entity);
        } else {
            pricingRuleMapper.updateById(entity);
        }
        return entity;
    }

    @Override
    @Transactional
    public void deleteRule(Long id) {
        if (id != null) {
            pricingRuleMapper.deleteById(id);
        }
    }

    private String normalizeScope(String scopeType, Set<String> allowed) {
        String value = scopeType == null ? "" : scopeType.trim().toUpperCase();
        if (!allowed.contains(value)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的作用域类型：" + scopeType);
        }
        return value;
    }

    private String normalizeUpper(String value, Set<String> allowed, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String upper = value.trim().toUpperCase();
        if (!allowed.contains(upper)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的取值：" + value);
        }
        return upper;
    }

    private long requireScopeRef(Long scopeRef) {
        if (scopeRef == null || scopeRef <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该作用域需要指定关联 ID");
        }
        return scopeRef;
    }

    private Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
