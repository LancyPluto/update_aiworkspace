package com.aiminilab.aitoolmarket.credit.support;

import java.util.Locale;
import java.util.Optional;

public enum MembershipTier {
    STARTER("starter", "标准版", 0),
    GROWTH("growth", "进阶版", 1),
    PRO("pro", "高级版", 2),
    FLAGSHIP("flagship", "豪华版", 3);

    private final String code;
    private final String displayName;
    private final int rank;

    MembershipTier(String code, String displayName, int rank) {
        this.code = code;
        this.displayName = displayName;
        this.rank = rank;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public int rank() {
        return rank;
    }

    public boolean meetsOrExceeds(MembershipTier required) {
        return required != null && rank >= required.rank;
    }

    public static Optional<MembershipTier> fromCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawCode.trim().toLowerCase(Locale.ROOT);
        for (MembershipTier tier : values()) {
            if (tier.code.equals(normalized)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    public static Optional<MembershipTier> fromPackageCode(String packageCode) {
        if (packageCode == null || packageCode.isBlank()) {
            return Optional.empty();
        }
        int separator = packageCode.lastIndexOf('_');
        if (separator < 0 || separator == packageCode.length() - 1) {
            return Optional.empty();
        }
        return fromCode(packageCode.substring(separator + 1));
    }
}
