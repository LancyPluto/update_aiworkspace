package com.aiminilab.aitoolmarket.credit.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of the unified pricing computation. Both pre-execution estimates and post-execution
 * settlement go through the same path; the only difference is whether real usage was supplied.
 *
 * @param vendorCost     raw vendor cost in CNY (after parameter rules), 0 when fallback was used
 * @param baseCredits    vendor cost converted to credits (no markup), incl. additive rule credits
 * @param markupRatio    applied markup ratio (e.g. 1.20)
 * @param chargeCredits  final user-facing credits (markup + floor applied)
 * @param modelDerived   true when the amount was computed from model pricing (markup applies),
 *                       false when a static tool fallback was used (returned as-is)
 * @param breakdown      itemised contributions for UI display
 */
public record PricingQuote(
        BigDecimal vendorCost,
        int baseCredits,
        BigDecimal markupRatio,
        int chargeCredits,
        boolean modelDerived,
        List<PricingBreakdownItem> breakdown
) {
}
