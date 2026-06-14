package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.PricingMarginUpsertRequest;
import com.aiminilab.aitoolmarket.admin.dto.PricingRuleUpsertRequest;
import com.aiminilab.aitoolmarket.credit.entity.PricingMargin;
import com.aiminilab.aitoolmarket.credit.entity.PricingRule;

import java.util.List;

public interface PricingConfigAdminService {

    List<PricingMargin> listMargins();

    PricingMargin saveMargin(PricingMarginUpsertRequest request);

    void deleteMargin(Long id);

    List<PricingRule> listRules();

    PricingRule saveRule(PricingRuleUpsertRequest request);

    void deleteRule(Long id);
}
