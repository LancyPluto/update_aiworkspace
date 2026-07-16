package com.aiminilab.aitoolmarket.admin.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyRoutingRulesTest {

    @Test
    void wildcardMatchesOnlySubdomainsWhileSuffixIncludesRoot() {
        ProxyRoutingRule wildcard = rule("wild", "WILDCARD", "*.example.com", "PROXY", 100);
        ProxyRoutingRule suffix = rule("suffix", "SUFFIX", "example.com", "DIRECT", 100);

        assertThat(ProxyRoutingRules.matches(wildcard, "example.com")).isFalse();
        assertThat(ProxyRoutingRules.matches(wildcard, "api.example.com")).isTrue();
        assertThat(ProxyRoutingRules.matches(suffix, "example.com")).isTrue();
        assertThat(ProxyRoutingRules.matches(suffix, "api.example.com")).isTrue();
    }

    @Test
    void orderingUsesSpecificityThenLongerDomainThenPriority() {
        List<ProxyRoutingRule> sorted = ProxyRoutingRules.sorted(List.of(
                rule("root-high", "SUFFIX", "example.com", "PROXY", 900),
                rule("long-low", "SUFFIX", "api.example.com", "DIRECT", 1),
                rule("exact", "EXACT", "api.example.com", "PROXY", 0),
                rule("wild", "WILDCARD", "*.example.com", "AUTO", 500)
        ));

        assertThat(sorted).extracting(ProxyRoutingRule::id)
                .containsExactly("exact", "long-low", "root-high", "wild");
    }

    private ProxyRoutingRule rule(String id, String type, String pattern, String strategy, int priority) {
        return new ProxyRoutingRule(id, type, pattern, strategy, priority, true, "", "");
    }
}
