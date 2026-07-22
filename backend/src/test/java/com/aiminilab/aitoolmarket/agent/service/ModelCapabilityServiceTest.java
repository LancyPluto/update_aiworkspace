package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCapabilityServiceTest {

    @Mock
    private AgentModelConfigMapper agentModelConfigMapper;

    @Mock
    private ModelConfigCredentialResolver credentialResolver;

    @Mock
    private ModelProviderMetadataService providerMetadataService;

    private ModelCapabilityService modelCapabilityService;

    @BeforeEach
    void setUp() {
        ModelCapabilitiesCodec codec = new ModelCapabilitiesCodec(new ObjectMapper());
        modelCapabilityService = new ModelCapabilityService(
                new ModelProviderRegistry(),
                providerMetadataService,
                codec,
                agentModelConfigMapper,
                credentialResolver
        );
        lenient().when(credentialResolver.resolveForExecution(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(providerMetadataService.get(anyString())).thenAnswer(invocation -> provider(
                invocation.getArgument(0),
                List.of(
                        "TEXT_GENERATION",
                        "VISION_INPUT",
                        "IMAGE_GENERATION",
                        "VIDEO_GENERATION",
                        "TEXT_TO_SPEECH",
                        "SPEECH_TO_TEXT",
                        "MUSIC_GENERATION",
                        "AUDIO_GENERATION",
                        "EMBEDDING",
                        "RERANK"
                )
        ));
        lenient().when(providerMetadataService.get("agnes_chat")).thenReturn(textProvider("agnes_chat"));
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
    void resolveModelConfigForTool_skipsDisabledCapabilityFallback() {
        AiTool ttsTool = new AiTool();
        ttsTool.setId(75L);
        ttsTool.setExecutionHandler("TEXT_TO_SPEECH");

        AgentModelConfig disabledSiliconflow = config(2L, "siliconflow_voice_tts", "[\"TEXT_TO_SPEECH\"]", false);
        disabledSiliconflow.setEnabled(false);
        disabledSiliconflow.setProvider("siliconflow_speech");
        AgentModelConfig enabledMinimax = config(26L, "minimax-speech-hd", "[\"TEXT_TO_SPEECH\"]", false);
        enabledMinimax.setProvider("minimax_speech");

        when(agentModelConfigMapper.findAllActive()).thenReturn(List.of(disabledSiliconflow, enabledMinimax));

        AgentModelConfig resolved = modelCapabilityService.resolveModelConfigForTool(ttsTool);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getConfigCode()).isEqualTo("minimax-speech-hd");
    }

    @Test
    void resolveModelConfigForTool_usesExplicitBindingWhenPresent() {
        AiTool digitalHumanTool = new AiTool();
        digitalHumanTool.setId(20L);
        digitalHumanTool.setModelConfigId(99L);
        digitalHumanTool.setExecutionHandler("DIGITAL_HUMAN");

        AgentModelConfig bound = config(99L, "seedance_video", "[\"VIDEO_GENERATION\"]", false);
        when(agentModelConfigMapper.findActiveById(99L)).thenReturn(bound);

        AgentModelConfig resolved = modelCapabilityService.resolveModelConfigForTool(digitalHumanTool);

        assertThat(resolved).isEqualTo(bound);
    }

    @Test
    void resolveModelConfigForTool_requiresEveryConfiguredToolCapability() {
        AiTool multimodalTool = new AiTool();
        multimodalTool.setRequiredModelCapabilities("[\"TEXT_GENERATION\",\"VISION_INPUT\"]");

        AgentModelConfig textOnlyDefault = config(10L, "text_only", "[\"TEXT_GENERATION\"]", true);
        AgentModelConfig visionModel = config(
                11L,
                "vision_model",
                "[\"TEXT_GENERATION\",\"VISION_INPUT\"]",
                false
        );
        when(agentModelConfigMapper.findAllActive()).thenReturn(List.of(textOnlyDefault, visionModel));

        AgentModelConfig resolved = modelCapabilityService.resolveModelConfigForTool(multimodalTool);

        assertThat(resolved).isEqualTo(visionModel);
    }

    @Test
    void resolveModelConfigForTool_skipsUnsupportedDigitalHumanVideoProvider() {
        AiTool digitalHumanTool = new AiTool();
        digitalHumanTool.setExecutionHandler("DIGITAL_HUMAN");
        digitalHumanTool.setRequiredModelCapabilities("[\"VIDEO_GENERATION\"]");

        AgentModelConfig klingDefault = config(10L, "kling_video", "[\"VIDEO_GENERATION\"]", true);
        klingDefault.setProvider("kling_video");
        AgentModelConfig seedance = config(11L, "seedance_video", "[\"VIDEO_GENERATION\"]", false);
        seedance.setProvider("seedance");
        when(agentModelConfigMapper.findAllActive()).thenReturn(List.of(klingDefault, seedance));

        AgentModelConfig resolved = modelCapabilityService.resolveModelConfigForTool(digitalHumanTool);

        assertThat(resolved).isEqualTo(seedance);
    }

    @Test
    void resolveModelConfigForTool_ignoresStoredCapabilitiesRejectedByProviderMetadata() {
        AiTool videoTool = new AiTool();
        videoTool.setRequiredModelCapabilities("[\"VIDEO_GENERATION\"]");
        AgentModelConfig invalidDefault = config(
                20L,
                "seedream_mislabeled_as_video",
                "[\"VIDEO_GENERATION\"]",
                true
        );
        invalidDefault.setProvider("volcengine_images");
        AgentModelConfig seedance = config(21L, "seedance_video", "[\"VIDEO_GENERATION\"]", false);
        seedance.setProvider("seedance");
        when(providerMetadataService.get("volcengine_images"))
                .thenReturn(provider("volcengine_images", List.of("IMAGE_GENERATION")));
        when(providerMetadataService.get("seedance"))
                .thenReturn(provider("seedance", List.of("VIDEO_GENERATION")));
        when(agentModelConfigMapper.findAllActive()).thenReturn(List.of(invalidDefault, seedance));

        AgentModelConfig resolved = modelCapabilityService.resolveModelConfigForTool(videoTool);

        assertThat(resolved).isEqualTo(seedance);
    }

    @Test
    void validateToolModelBinding_rejectsModelMissingAnyRequiredCapability() {
        AiTool multimodalTool = new AiTool();
        multimodalTool.setModelConfigId(10L);
        multimodalTool.setRequiredModelCapabilities("[\"TEXT_GENERATION\",\"VISION_INPUT\"]");
        AgentModelConfig textOnly = config(10L, "text_only", "[\"TEXT_GENERATION\"]", true);
        when(agentModelConfigMapper.findActiveById(10L)).thenReturn(textOnly);

        assertThatThrownBy(() -> modelCapabilityService.validateToolModelBinding(multimodalTool))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VISION_INPUT");
    }

    @Test
    void validateToolModelBinding_rejectsUnsupportedDigitalHumanVideoProvider() {
        AiTool digitalHumanTool = new AiTool();
        digitalHumanTool.setModelConfigId(10L);
        digitalHumanTool.setExecutionHandler("DIGITAL_HUMAN");
        digitalHumanTool.setRequiredModelCapabilities("[\"VIDEO_GENERATION\"]");
        AgentModelConfig kling = config(10L, "kling_video", "[\"VIDEO_GENERATION\"]", false);
        kling.setProvider("kling_video");
        when(agentModelConfigMapper.findActiveById(10L)).thenReturn(kling);

        assertThatThrownBy(() -> modelCapabilityService.validateToolModelBinding(digitalHumanTool))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DIGITAL_HUMAN")
                .hasMessageContaining("seedance")
                .hasMessageContaining("infinitetalk");
    }

    @Test
    void validateToolModelCapabilities_checksCapabilitiesEvenWhenModelIsDisabled() {
        AiTool multimodalTool = new AiTool();
        multimodalTool.setRequiredModelCapabilities("[\"TEXT_GENERATION\",\"VISION_INPUT\"]");
        AgentModelConfig disabledTextOnly = config(10L, "disabled_text", "[\"TEXT_GENERATION\"]", false);
        disabledTextOnly.setEnabled(false);

        assertThatThrownBy(() -> modelCapabilityService.validateToolModelCapabilities(
                multimodalTool,
                disabledTextOnly
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VISION_INPUT");
    }

    @Test
    void resolveRequiredCapabilities_normalizesStoredValuesAndMapsLegacyDigitalHuman() {
        AiTool tool = new AiTool();
        tool.setRequiredModelCapabilities(
                "[\" image_generation \",\"DIGITAL_HUMAN\",\"IMAGE_GENERATION\"]"
        );

        assertThat(modelCapabilityService.resolveRequiredCapabilities(tool))
                .containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
    }

    @Test
    void resolveRequiredCapabilities_usesLegacyToolTypeMappingWhenStoredValueIsMissing() {
        AiTool tool = new AiTool();
        tool.setToolType("IMAGE_UNDERSTANDING");

        assertThat(modelCapabilityService.resolveRequiredCapabilities(tool))
                .containsExactly("TEXT_GENERATION", "VISION_INPUT");
    }

    @Test
    void resolveRequiredCapabilities_mapsLegacyDigitalHumanToolTypeToVideo() {
        AiTool tool = new AiTool();
        tool.setToolType("DIGITAL_HUMAN");

        assertThat(modelCapabilityService.resolveRequiredCapabilities(tool))
                .containsExactly("VIDEO_GENERATION");
    }

    @Test
    void resolveRequiredCapabilities_coversEveryLegacyToolTypeFallback() {
        List<CapabilityFallback> cases = List.of(
                new CapabilityFallback("IMAGE_TO_IMAGE", List.of("IMAGE_GENERATION")),
                new CapabilityFallback("IMAGE_UNDERSTANDING", List.of("TEXT_GENERATION", "VISION_INPUT")),
                new CapabilityFallback("SPEECH_TO_TEXT", List.of("SPEECH_TO_TEXT")),
                new CapabilityFallback("TEXT_TO_SPEECH", List.of("TEXT_TO_SPEECH")),
                new CapabilityFallback("EMBEDDING", List.of("EMBEDDING")),
                new CapabilityFallback("RERANK", List.of("RERANK")),
                new CapabilityFallback("AGENT", List.of("TEXT_GENERATION"))
        );

        for (CapabilityFallback fallback : cases) {
            AiTool tool = new AiTool();
            tool.setToolType(fallback.toolType());
            assertThat(modelCapabilityService.resolveRequiredCapabilities(tool))
                    .as(fallback.toolType())
                    .isEqualTo(fallback.capabilities());
        }
    }

    @Test
    void normalizeRequiredCapabilities_rejectsEmptyAndUnknownSelections() {
        assertThatThrownBy(() -> modelCapabilityService.normalizeRequiredCapabilities(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be empty");

        assertThatThrownBy(() -> modelCapabilityService.normalizeRequiredCapabilities(List.of("DIGITAL_HUMAN")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsupported model capability: DIGITAL_HUMAN");

        assertThatThrownBy(() -> modelCapabilityService.normalizeRequiredCapabilities(List.of("NOT_A_CAPABILITY")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsupported model capability");
    }

    @Test
    void normalizeRequiredCapabilities_acceptsKnownCapabilityWithoutConfiguredProvider() {
        assertThat(modelCapabilityService.normalizeRequiredCapabilities(List.of(" embedding ", "EMBEDDING")))
                .containsExactly("EMBEDDING");
    }

    @Test
    void normalizeCapabilities_rejectsVisionInputWhenProviderDoesNotDeclareIt() {
        assertThatThrownBy(() -> modelCapabilityService.normalizeCapabilities(
                "agnes_chat",
                List.of("TEXT_GENERATION", "VISION_INPUT")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not support capability VISION_INPUT");
    }

    @Test
    void validateModelExecution_rejectsProviderWithoutWorkerExecutor() {
        AgentModelConfig config = config(30L, "worker_not_ready", "[\"TEXT_GENERATION\"]", false);
        config.setProvider("worker_not_ready");
        when(providerMetadataService.get("worker_not_ready"))
                .thenReturn(provider("worker_not_ready", List.of("TEXT_GENERATION"), false));

        assertThatThrownBy(() -> modelCapabilityService.validateModelExecution(
                config,
                List.of("TEXT_GENERATION")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("worker executor is not ready");
    }

    @Test
    void validateModelExecution_doesNotRequireCredentialsForUnusedExtraCapabilities() {
        AgentModelConfig config = config(
                31L,
                "text_with_extra_image_capability",
                "[\"TEXT_GENERATION\",\"IMAGE_GENERATION\"]",
                false
        );

        assertThatCode(() -> modelCapabilityService.validateModelExecution(
                config,
                List.of("TEXT_GENERATION")
        )).doesNotThrowAnyException();
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

    private static ModelProviderResponse textProvider(String code) {
        return provider(code, List.of("TEXT_GENERATION"));
    }

    private static ModelProviderResponse provider(String code, List<String> capabilities) {
        return provider(code, capabilities, true);
    }

    private static ModelProviderResponse provider(String code, List<String> capabilities, boolean workerReady) {
        return new ModelProviderResponse(
                code,
                code,
                capabilities,
                "",
                "",
                "TOKEN_PER_M",
                "openai_compatible",
                "chat",
                "",
                "accept_only",
                workerReady,
                true,
                "",
                "test",
                null,
                null,
                ""
        );
    }

    private record CapabilityFallback(String toolType, List<String> capabilities) {
    }
}
