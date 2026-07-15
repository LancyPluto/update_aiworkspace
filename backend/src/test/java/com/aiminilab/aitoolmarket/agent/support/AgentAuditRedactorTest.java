package com.aiminilab.aitoolmarket.agent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAuditRedactorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentAuditRedactor redactor = new AgentAuditRedactor();

    @Test
    void removesSecretsSignedUrlsAndIdentityDataWithoutMutatingSource() throws Exception {
        JsonNode source = objectMapper.readTree("""
                {
                  "authorization": "Bearer raw-secret",
                  "nested": {
                    "apiKey": "sk-live-value",
                    "url": "https://files.example/a.png?x=1&X-OSS-Signature=signature-value&token=url-token",
                    "contact": "alice.long@example.com 13812345678 110101199001011234"
                  }
                }
                """);

        JsonNode result = redactor.redact(source);

        assertThat(source.path("authorization").asText()).isEqualTo("Bearer raw-secret");
        assertThat(result.path("authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(result.path("nested").path("apiKey").asText()).isEqualTo("[REDACTED]");
        assertThat(result.toString()).doesNotContain("signature-value", "url-token", "alice.long", "13812345678", "110101199001011234");
        assertThat(result.path("nested").path("contact").asText()).contains("a***@example.com", "138****5678", "110101********1234");
    }

    @Test
    void replacesDataUrlsWithStableHashMarker() throws Exception {
        JsonNode source = objectMapper.readTree("{\"content\":\"data:image/png;base64,AAAA\"}");

        JsonNode result = redactor.redact(source);

        assertThat(result.path("content").asText()).startsWith("[DATA_URL sha256=").endsWith("]");
        assertThat(result.path("content").asText()).doesNotContain("AAAA");
    }
}
