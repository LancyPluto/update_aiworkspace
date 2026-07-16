package com.aiminilab.aitoolmarket.admin.proxy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MihomoConfigRendererTest {

    private final MihomoConfigRenderer renderer = new MihomoConfigRenderer();

    @Test
    void rendersSubscriptionProviderWithControllerAndDirectRulesBeforeMatch() {
        Map<String, String> settings = baseSettings();
        settings.put("outbound.proxy.sourceType", "SUBSCRIPTION");
        settings.put("outbound.proxy.subscriptionUrl", "https://airport.example.com/sub?token=a'b");
        settings.put("outbound.proxy.subscriptionUpdateIntervalMinutes", "30");
        settings.put("outbound.proxy.noProxyHosts", "localhost,backend,.aliyun.com,8.8.8.8");

        String yaml = renderer.render(settings, "controller's-secret");

        assertThat(yaml).contains(
                "external-controller: '0.0.0.0:9090'",
                "secret: 'controller''s-secret'",
                "proxy-providers:",
                "url: 'https://airport.example.com/sub?token=a''b'",
                "interval: 1800",
                "store-selected: true",
                "name: 'AUTO-NODE'",
                "type: url-test",
                "use:\n      - 'subscription'"
        );
        assertThat(yaml).doesNotContain("proxies:\n  - name: 'manual-node'");
        assertThat(yaml.indexOf("DOMAIN-SUFFIX,aliyun.com,DIRECT"))
                .isLessThan(yaml.indexOf("MATCH,DIRECT"));
        assertThat(yaml.indexOf("DOMAIN,backend,DIRECT"))
                .isLessThan(yaml.indexOf("MATCH,DIRECT"));
        assertThat(yaml.indexOf("IP-CIDR,8.8.8.8/32,DIRECT,no-resolve"))
                .isLessThan(yaml.indexOf("MATCH,DIRECT"));
        assertThat(yaml.trim()).endsWith("- 'MATCH,DIRECT'");
    }

    @Test
    void rendersHttpManualNodeAndQuotesCredentials() {
        Map<String, String> settings = baseSettings();
        settings.put("outbound.proxy.sourceType", "MANUAL");
        settings.put("outbound.proxy.manualProtocol", "HTTP");
        settings.put("outbound.proxy.manualHost", "8.8.4.4");
        settings.put("outbound.proxy.manualPort", "8080");
        settings.put("outbound.proxy.manualUsername", "ops'user");
        settings.put("outbound.proxy.manualPassword", "p'ass:word");

        String yaml = renderer.render(settings, "secret");

        assertThat(yaml).contains(
                "proxies:",
                "name: 'manual-node'",
                "type: 'http'",
                "server: '8.8.4.4'",
                "port: 8080",
                "username: 'ops''user'",
                "password: 'p''ass:word'",
                "proxies:\n      - 'manual-node'"
        );
        assertThat(yaml).doesNotContain("proxy-providers:");
    }

    @Test
    void rendersSocks5AndHttpsManualProtocols() {
        Map<String, String> settings = baseSettings();
        settings.put("outbound.proxy.sourceType", "MANUAL");
        settings.put("outbound.proxy.manualHost", "1.1.1.1");
        settings.put("outbound.proxy.manualPort", "1080");
        settings.put("outbound.proxy.manualProtocol", "SOCKS5");

        assertThat(renderer.render(settings, "secret"))
                .contains("type: 'socks5'")
                .doesNotContain("tls: true");

        settings.put("outbound.proxy.manualProtocol", "HTTPS");
        assertThat(renderer.render(settings, "secret"))
                .contains("type: 'http'", "tls: true");
    }

    @Test
    void rendersNewlineSeparatedDirectHostsAsSeparateRules() {
        Map<String, String> settings = baseSettings();
        settings.put("outbound.proxy.sourceType", "SUBSCRIPTION");
        settings.put("outbound.proxy.subscriptionUrl", "https://airport.example.com/sub");
        settings.put("outbound.proxy.noProxyHosts", "localhost\nbackend\n.aliyun.com");

        String yaml = renderer.render(settings, "secret");

        assertThat(yaml).contains(
                "'DOMAIN,localhost,DIRECT'",
                "'DOMAIN,backend,DIRECT'",
                "'DOMAIN-SUFFIX,aliyun.com,DIRECT'"
        );
    }

    @Test
    void rendersPublicRoutingRulesBySpecificityBeforeFallbackAndUsesSafeAutoProbe() {
        Map<String, String> settings = baseSettings();
        settings.put("outbound.proxy.sourceType", "MANUAL");
        settings.put("outbound.proxy.manualHost", "8.8.8.8");
        settings.put("outbound.proxy.routing", """
                {
                  "rules": [
                    {"id":"suffix","patternType":"SUFFIX","pattern":"example.com","strategy":"PROXY","priority":900,"enabled":true,"note":""},
                    {"id":"exact","patternType":"EXACT","pattern":"api.example.com","strategy":"DIRECT","priority":10,"enabled":true,"note":""},
                    {"id":"wild","patternType":"WILDCARD","pattern":"*.media.example.net","strategy":"AUTO","priority":500,"enabled":true,"note":"","probeUrl":"https://health.example.net/ping"},
                    {"id":"disabled","patternType":"EXACT","pattern":"disabled.example.com","strategy":"DIRECT","priority":999,"enabled":false,"note":""}
                  ],
                  "autoSettings":{"timeoutMs":5000,"sampleSize":6,"switchThresholdMs":150,"hysteresisMs":80,"cooldownSeconds":300}
                }
                """);

        String yaml = renderer.render(settings, "secret");

        assertThat(yaml).contains(
                "name: 'AUTO-wild'",
                "type: url-test",
                "url: 'https://health.example.net/ping'",
                "interval: 300",
                "tolerance: 80",
                "'DOMAIN,api.example.com,DIRECT'",
                "'DOMAIN-SUFFIX,example.com,PROXY'",
                "'DOMAIN-WILDCARD,*.media.example.net,AUTO-wild'"
        ).doesNotContain("disabled.example.com");
        assertThat(yaml.indexOf("DOMAIN,api.example.com,DIRECT"))
                .isLessThan(yaml.indexOf("DOMAIN-SUFFIX,example.com,PROXY"));
        assertThat(yaml.indexOf("DOMAIN-WILDCARD,*.media.example.net,AUTO-wild"))
                .isLessThan(yaml.indexOf("DOMAIN-SUFFIX,example.com,PROXY"));
        assertThat(yaml.indexOf("DOMAIN-WILDCARD,*.media.example.net,AUTO-wild"))
                .isLessThan(yaml.indexOf("MATCH,DIRECT"));
    }

    private Map<String, String> baseSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("outbound.proxy.noProxyHosts", "localhost,127.0.0.1");
        return settings;
    }
}
