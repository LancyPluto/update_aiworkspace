package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.dto.AgentSkillDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentSkillBundle;
import com.aiminilab.aitoolmarket.agent.mapper.AgentSkillBundleMapper;
import com.aiminilab.aitoolmarket.agent.service.impl.AgentSkillBundleServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSkillBundleServiceImplTest {

    @Mock
    AgentSkillBundleMapper mapper;

    @Test
    void musicSkillIsVisibleWhenMatchingMusicToolIsAvailable() {
        when(mapper.selectPublished()).thenReturn(List.of(musicSkill()));
        AgentSkillBundleServiceImpl service = new AgentSkillBundleServiceImpl(mapper, new ObjectMapper());

        List<AgentSkillDescriptorResponse> descriptors = service.listAvailableSkillDescriptors(List.of(tool("suno_music")));

        assertThat(descriptors).extracting(AgentSkillDescriptorResponse::skillCode).containsExactly("music_generation");
        assertThat(descriptors.get(0).toolCodes()).contains("suno_music", "suno", "music_generation");
    }

    @Test
    void musicSkillIsHiddenWhenOnlyNonMatchingToolsAreAvailable() {
        when(mapper.selectPublished()).thenReturn(List.of(musicSkill()));
        AgentSkillBundleServiceImpl service = new AgentSkillBundleServiceImpl(mapper, new ObjectMapper());

        List<AgentSkillDescriptorResponse> descriptors = service.listAvailableSkillDescriptors(List.of(tool("image_generation")));

        assertThat(descriptors).isEmpty();
    }

    @Test
    void noSkillsAreVisibleWhenUserHasNoAvailableTools() {
        AgentSkillBundleServiceImpl service = new AgentSkillBundleServiceImpl(mapper, new ObjectMapper());

        List<AgentSkillDescriptorResponse> descriptors = service.listAvailableSkillDescriptors(List.of());

        assertThat(descriptors).isEmpty();
        verifyNoInteractions(mapper);
    }

    private static AgentSkillBundle musicSkill() {
        AgentSkillBundle bundle = new AgentSkillBundle();
        bundle.setId(1L);
        bundle.setSkillCode("music_generation");
        bundle.setDisplayName("音乐生成");
        bundle.setDescription("Suno music generation");
        bundle.setToolCodesJson("[\"suno_music\",\"suno\",\"music_generation\"]");
        bundle.setSopRules("Keep non-custom prompts under 500 characters.");
        bundle.setStatus("PUBLISHED");
        bundle.setVersion(1);
        return bundle;
    }

    private static AgentToolDescriptorResponse tool(String toolCode) {
        return new AgentToolDescriptorResponse(
                toolCode,
                toolCode,
                "",
                0,
                Map.of(),
                true,
                List.of(),
                Map.of()
        );
    }
}
