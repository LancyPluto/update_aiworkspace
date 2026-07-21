package com.aiminilab.aitoolmarket.agent.connectivity;

import org.junit.jupiter.api.Test;

import static com.aiminilab.aitoolmarket.agent.connectivity.AccountProbeErrorClassifier.Decision.FAIL;
import static com.aiminilab.aitoolmarket.agent.connectivity.AccountProbeErrorClassifier.Decision.FALLBACK;
import static com.aiminilab.aitoolmarket.agent.connectivity.AccountProbeErrorClassifier.Decision.PASS;
import static com.aiminilab.aitoolmarket.agent.connectivity.AccountProbeErrorClassifier.Decision.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

class AccountProbeErrorClassifierTest {

    private final AccountProbeErrorClassifier classifier = new AccountProbeErrorClassifier();

    @Test
    void classifiesSuccessCredentialAndBillingResponses() {
        assertThat(classifier.classify(200, "{}").decision()).isEqualTo(PASS);
        assertThat(classifier.classify(299, "{}").decision()).isEqualTo(PASS);
        assertThat(classifier.classify(401, "unauthorized").decision()).isEqualTo(FAIL);
        assertThat(classifier.classify(403, "invalid api key").decision()).isEqualTo(FAIL);
        assertThat(classifier.classify(403, "access denied").decision()).isEqualTo(FAIL);
        assertThat(classifier.classify(402, "payment required").decision()).isEqualTo(WARNING);
        assertThat(classifier.classify(403, "free quota has been exhausted").decision()).isEqualTo(WARNING);
        assertThat(classifier.classify(429, "too many requests").decision()).isEqualTo(WARNING);
    }

    @Test
    void onlyUnsupportedModelsStatusesRequestFallback() {
        assertThat(classifier.classify(404, "not found").decision()).isEqualTo(FALLBACK);
        assertThat(classifier.classify(405, "method not allowed").decision()).isEqualTo(FALLBACK);
        assertThat(classifier.classify(501, "not implemented").decision()).isEqualTo(FALLBACK);
        assertThat(classifier.classify(500, "insufficient balance").decision()).isEqualTo(FAIL);
        assertThat(classifier.classify(503, "unavailable").decision()).isEqualTo(FAIL);
    }
}
