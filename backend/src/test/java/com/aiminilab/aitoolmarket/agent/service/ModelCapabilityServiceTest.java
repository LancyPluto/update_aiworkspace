package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCapabilityServiceTest {

    @Mock
    private AgentModelConfigMapper agentModelConfigMapper;

    private ModelCapabilityService modelCapabilityService;

    @BeforeEach
    void setUp() {
        ModelCapabilitiesCodec codec = new ModelCapabilitiesCodec(new ObjectMapper());
        modelCapabilityService = new ModelCapabilityService(
                new ModelProviderRegistry(),
                codec,
                agentModelConfigMapper
        );
    }

    @Test
    void resolveModelConfigForTool_prefersCapabilityMatchOverUnrelatedDefault() {
        AiTool copywritingTool = new AiTool();
        copywritingTool.setId(10L);
        copywritingTool.setToolType("TEXT_GENERATION");

        AgentModelConfig digitalHuman = config(1L, "siliconflow_digital_human", "[\"IMAGE_GENERATION\",\"DIGITAL_HUMAN\"]", true);
        AgentModelConfig textDefault = config(2L, "default_text_generation", "[\"TEXT_GENERATION\"]", true);

        when(agentModelConfigMapper.findAllActive()).thenReturn(List.of(digitalHuman, textDefault));

        AgentModelConfig resolved = modelCapabilityService.resolveModelConfigForTool(copywritingTool);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getConfigCode()).isEqualTo("default_text_generation");
    }

    @Test
    void validateExecution_throwsWhenModelConfigMissing() {
        AiTool tool = new AiTool();
        tool.setToolType("TEXT_GENERATION");

        assertThatThrownBy(() -> modelCapabilityService.validateExecution(tool, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void resolveModelConfigForTool_usesExplicitBindingWhenPresent() {
        AiTool digitalHumanTool = new AiTool();
        digitalHumanTool.setId(20L);
        digitalHumanTool.setModelConfigId(99L);
        digitalHumanTool.setExecutionHandler("DIGITAL_HUMAN");

        AgentModelConfig bound = config(99L, "siliconflow_digital_human", "[\"DIGITAL_HUMAN\"]", false);
        when(agentModelConfigMapper.findActiveById(99L)).thenReturn(bound);

        AgentModelConfig resolved = modelCapabilityService.resolveModelConfigForTool(digitalHumanTool);

        assertThat(resolved).isEqualTo(bound);
    }

    private static AgentModelConfig config(Long id, String code, String capabilitiesJson, boolean isDefault) {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(id);
        config.setConfigCode(code);
        config.setProvider("minimax");
        config.setCapabilities(capabilitiesJson);
        config.setDefault(isDefault);
        config.setEnabled(true);
        return config;
    }
}
