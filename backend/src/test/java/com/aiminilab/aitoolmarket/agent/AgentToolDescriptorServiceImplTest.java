package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolDescriptorExtensionMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolPreferenceMapper;
import com.aiminilab.aitoolmarket.agent.service.impl.AgentToolDescriptorServiceImpl;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AgentToolDescriptorServiceImplTest {

    @Mock
    ToolMapper toolMapper;
    @Mock
    ToolFieldItemMapper toolFieldItemMapper;
    @Mock
    AgentToolDescriptorExtensionMapper extensionMapper;
    @Mock
    AgentToolPreferenceMapper preferenceMapper;
    @Mock
    AgentModelConfigMapper modelConfigMapper;
    @Mock
    TaskCreditEstimateService taskCreditEstimateService;

    @Test
    void customModeOptionsObjectCompilesToBooleanEnumForAgentSchema() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentToolDescriptorServiceImpl service = new AgentToolDescriptorServiceImpl(
                toolMapper,
                toolFieldItemMapper,
                extensionMapper,
                preferenceMapper,
                modelConfigMapper,
                taskCreditEstimateService,
                objectMapper
        );
        ToolFieldResponse customMode = new ToolFieldResponse(
                "customMode",
                "创作模式",
                "radio",
                "常规：仅描述想法；高级：自定义歌词、风格与标题",
                objectMapper.readTree("{\"options\":[{\"label\":\"常规\",\"value\":\"false\"},{\"label\":\"高级\",\"value\":\"true\"}]}"),
                "{\"options\":[{\"label\":\"常规\",\"value\":\"false\"},{\"label\":\"高级\",\"value\":\"true\"}]}",
                true,
                true,
                false,
                "false",
                "default",
                "LOW",
                3
        );

        Method method = AgentToolDescriptorServiceImpl.class.getDeclaredMethod("toInputSchema", String.class, List.class);
        method.setAccessible(true);
        JsonNode schema = (JsonNode) method.invoke(service, "suno_music", List.of(customMode));
        JsonNode property = schema.path("properties").path("customMode");

        assertThat(property.path("type").asText()).isEqualTo("boolean");
        assertThat(property.path("enum").get(0).asBoolean()).isFalse();
        assertThat(property.path("enum").get(1).asBoolean()).isTrue();
        assertThat(property.path("default").asBoolean()).isFalse();
        assertThat(schema.path("required").get(0).asText()).isEqualTo("customMode");
    }
}
