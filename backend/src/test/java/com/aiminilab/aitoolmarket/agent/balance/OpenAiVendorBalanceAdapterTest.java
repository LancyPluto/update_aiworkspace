package com.aiminilab.aitoolmarket.agent.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiVendorBalanceAdapterTest {

    @Test
    void supportsOnlyOfficialOpenAiVendor() {
        OpenAiVendorBalanceAdapter adapter = new OpenAiVendorBalanceAdapter(new ObjectMapper());

        assertThat(adapter.supports("openai")).isTrue();
        assertThat(adapter.supports("OPENAI")).isTrue();
        assertThat(adapter.supports("ofox")).isFalse();
        assertThat(adapter.supports("openai_gateway")).isFalse();
    }
}
