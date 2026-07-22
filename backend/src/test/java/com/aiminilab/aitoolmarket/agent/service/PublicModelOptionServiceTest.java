package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelOptionGroupResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.support.ImageGenerationParameterResolver;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicModelOptionServiceTest {

    @Test
    void list_digitalHumanModeOnlyReturnsImplementedVideoProviders() {
        AgentModelConfigMapper modelMapper = mock(AgentModelConfigMapper.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AgentModelConfig seedance = config(
                1L,
                "seedance",
                "seedance",
                "[\"VIDEO_GENERATION\",\"DIGITAL_HUMAN\"]"
        );
        AgentModelConfig infiniteTalk = config(
                2L,
                "infinitetalk",
                "infinitetalk",
                "[\"VIDEO_GENERATION\"]"
        );
        AgentModelConfig agnesVideo = config(
                3L,
                "agnes-video",
                "agnes_video",
                "[\"VIDEO_GENERATION\"]"
        );
        AgentModelConfig legacyDigitalHuman = config(
                4L,
                "legacy",
                "seedance",
                "[\"DIGITAL_HUMAN\"]"
        );
        when(accountMapper.findAllActive()).thenReturn(List.of());
        when(modelMapper.findAgentEnabled()).thenReturn(List.of(
                seedance,
                infiniteTalk,
                agnesVideo,
                legacyDigitalHuman
        ));
        when(capabilityService.resolveCapabilities(seedance)).thenReturn(List.of("VIDEO_GENERATION"));
        when(capabilityService.resolveCapabilities(infiniteTalk)).thenReturn(List.of("VIDEO_GENERATION"));
        when(capabilityService.resolveCapabilities(agnesVideo)).thenReturn(List.of("VIDEO_GENERATION"));
        when(capabilityService.resolveCapabilities(legacyDigitalHuman)).thenReturn(List.of());
        when(capabilityService.isDigitalHumanVideoProvider("seedance")).thenReturn(true);
        when(capabilityService.isDigitalHumanVideoProvider("infinitetalk")).thenReturn(true);
        when(vendorCodeResolver.resolveVendorCode(seedance)).thenReturn("volcengine");
        when(vendorCodeResolver.resolveVendorCode(infiniteTalk)).thenReturn("infinitetalk");
        when(vendorCodeResolver.vendorLabel("volcengine")).thenReturn("Volcengine");
        when(vendorCodeResolver.vendorLabel("infinitetalk")).thenReturn("InfiniteTalk");
        when(vendorCodeResolver.vendorIconAsset("volcengine")).thenReturn("doubao");
        when(vendorCodeResolver.vendorIconAsset("infinitetalk")).thenReturn("infinitetalk");
        PublicModelOptionService service = new PublicModelOptionService(
                modelMapper,
                accountMapper,
                vendorCodeResolver,
                mock(ImageGenerationParameterResolver.class),
                capabilityService
        );

        List<ModelOptionGroupResponse> result = service.list("digital_human");

        assertThat(result).hasSize(2);
        assertThat(result).flatExtracting(ModelOptionGroupResponse::models)
                .extracting("configCode")
                .containsExactlyInAnyOrder("seedance", "infinitetalk");
        assertThat(result).flatExtracting(ModelOptionGroupResponse::models)
                .allSatisfy(model -> assertThat(model.capabilities()).containsExactly("VIDEO_GENERATION"));
    }

    @Test
    void list_excludesStoredCapabilityRejectedByProviderMetadata() {
        AgentModelConfigMapper modelMapper = mock(AgentModelConfigMapper.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AgentModelConfig mislabeledVideo = config(
                1L,
                "mislabeled",
                "seedance",
                "[\"VIDEO_GENERATION\"]"
        );
        when(accountMapper.findAllActive()).thenReturn(List.of());
        when(modelMapper.findAgentEnabled()).thenReturn(List.of(mislabeledVideo));
        when(capabilityService.resolveCapabilities(mislabeledVideo)).thenReturn(List.of());
        PublicModelOptionService service = new PublicModelOptionService(
                modelMapper,
                accountMapper,
                vendorCodeResolver,
                mock(ImageGenerationParameterResolver.class),
                capabilityService
        );

        assertThat(service.list("video")).isEmpty();
    }

    private static AgentModelConfig config(Long id, String code, String provider, String capabilities) {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(id);
        config.setConfigCode(code);
        config.setDisplayName(code);
        config.setModelName(code);
        config.setProvider(provider);
        config.setCapabilities(capabilities);
        config.setEnabled(true);
        return config;
    }
}
