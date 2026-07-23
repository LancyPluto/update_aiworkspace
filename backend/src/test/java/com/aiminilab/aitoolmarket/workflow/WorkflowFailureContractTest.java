package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.support.WorkflowFailureContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowFailureContractTest {

    @AfterEach
    void clearTraceId() {
        MDC.remove("traceId");
    }

    @Test
    void providerDiagnosticsAreSanitizedAndNeverBecomeUserMessage() {
        MDC.put("traceId", "trace-workflow-1");

        WorkflowFailureContract failure = WorkflowFailureContract.from(
                "PROVIDER_FAILED",
                "provider=minimax password=secret-value",
                "provider=minimax Authorization=Bearer provider-token responseBody={\"secret\":\"raw\"}"
        );

        assertThat(failure.errorCode()).isEqualTo("PROVIDER_FAILED");
        assertThat(failure.userMessage()).isEqualTo("工作流执行失败，请稍后重试");
        assertThat(failure.developerMessage())
                .contains("provider=minimax", "[REDACTED]")
                .doesNotContain("provider-token", "raw");
        assertThat(failure.failureTraceId()).isEqualTo("trace-workflow-1");
    }

    @Test
    void onlyWhitelistedCodesProduceSpecificUserMessages() {
        assertThat(WorkflowFailureContract.userMessage("MODEL_TIMEOUT"))
                .isEqualTo("工作流执行超时，请稍后重试");
        assertThat(WorkflowFailureContract.userMessage("MODEL_RATE_LIMITED"))
                .isEqualTo("请求过于频繁，请稍后重试");
        assertThat(WorkflowFailureContract.userMessage("vendor-raw-error"))
                .isEqualTo("工作流执行失败，请稍后重试");
    }
}
