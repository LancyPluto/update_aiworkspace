package com.aiminilab.aitoolmarket.admin.proxy;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProxyRoutingRules {
    private ProxyRoutingRules() {
    }

    public static List<ProxyRoutingRule> sorted(List<ProxyRoutingRule> rules) {
        return rules.stream().sorted(comparator()).toList();
    }

    public static Comparator<ProxyRoutingRule> comparator() {
        return Comparator
                .comparingInt((ProxyRoutingRule rule) -> specificity(rule.patternType()))
                .thenComparing(Comparator.comparingInt((ProxyRoutingRule rule) -> base(rule.pattern()).length()).reversed())
                .thenComparing(Comparator.comparingInt(ProxyRoutingRule::priority).reversed())
                .thenComparing(rule -> normalized(rule.pattern()))
                .thenComparing(rule -> normalized(rule.id()));
    }

    public static boolean matches(ProxyRoutingRule rule, String rawHost) {
        String host = normalized(rawHost);
        String pattern = normalized(rule.pattern());
        String base = base(pattern);
        return switch (normalized(rule.patternType()).toUpperCase(Locale.ROOT)) {
            case "EXACT" -> host.equals(base);
            case "WILDCARD" -> host.endsWith("." + base) && !host.equals(base);
            default -> host.equals(base) || host.endsWith("." + base);
        };
    }

    public static String base(String pattern) {
        String value = normalized(pattern);
        return value.startsWith("*.") ? value.substring(2) : value.startsWith(".") ? value.substring(1) : value;
    }

    private static int specificity(String patternType) {
        return switch (normalized(patternType).toUpperCase(Locale.ROOT)) {
            case "EXACT" -> 0;
            default -> 1;
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
