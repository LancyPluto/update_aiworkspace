package com.aiminilab.aitoolmarket.ppt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PptModelInvocationServiceTest {

    private final PptModelInvocationService service = new PptModelInvocationService(
            null, null, null, null, null, null, new ObjectMapper());

    @Test
    void preservesJsonOutlineAsTextForBananaTextProvider() {
        String outline = """
                [{"title":"平台定位","points":["一站式生成"]}]
                """.trim();

        var result = service.normalizeResult("TEXT_GENERATION", outline);

        assertThat(result.isObject()).isTrue();
        assertThat(result.path("text").asText()).isEqualTo(outline);
    }

    @Test
    void keepsImageTaskResultStructuredForUrlDiscovery() {
        String imageResult = """
                {"images":[{"url":"/generated/ppt/page-1.png"}]}
                """.trim();

        var result = service.normalizeResult("IMAGE_GENERATION", imageResult);

        assertThat(result.path("images").get(0).path("url").asText())
                .isEqualTo("/generated/ppt/page-1.png");
    }
}
