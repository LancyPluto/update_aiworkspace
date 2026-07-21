package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.entity.ComicCharacter;
import com.aiminilab.aitoolmarket.comic.entity.ComicCharacterVersion;
import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.aiminilab.aitoolmarket.comic.entity.ComicScene;
import com.aiminilab.aitoolmarket.comic.entity.ComicSceneVersion;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterVersionMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectWorkflowRunMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneVersionMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicWorkflowProjectionMapper;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowResultProjector;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComicWorkflowResultProjectorTest {
    private ComicProjectWorkflowRunMapper workflowRunMapper;
    private ComicWorkflowProjectionMapper projectionMapper;
    private ComicEpisodeMapper episodeMapper;
    private ComicShotMapper shotMapper;
    private ComicCharacterMapper characterMapper;
    private ComicCharacterVersionMapper characterVersionMapper;
    private ComicSceneMapper sceneMapper;
    private ComicSceneVersionMapper sceneVersionMapper;
    private WorkflowRunMapper workflowMapper;
    private ComicWorkflowResultProjector projector;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        workflowRunMapper = mock(ComicProjectWorkflowRunMapper.class);
        projectionMapper = mock(ComicWorkflowProjectionMapper.class);
        episodeMapper = mock(ComicEpisodeMapper.class);
        shotMapper = mock(ComicShotMapper.class);
        characterMapper = mock(ComicCharacterMapper.class);
        characterVersionMapper = mock(ComicCharacterVersionMapper.class);
        sceneMapper = mock(ComicSceneMapper.class);
        sceneVersionMapper = mock(ComicSceneVersionMapper.class);
        workflowMapper = mock(WorkflowRunMapper.class);
        projector = new ComicWorkflowResultProjector(
                workflowRunMapper, projectionMapper, episodeMapper, shotMapper,
                characterMapper, characterVersionMapper, sceneMapper, sceneVersionMapper,
                workflowMapper, new ObjectMapper()
        );
        objectMapper = new ObjectMapper();
        when(workflowRunMapper.selectByWorkflowRunInternal(91L)).thenReturn(binding());
        when(projectionMapper.complete(91L, "SUCCESS")).thenReturn(1);
        when(projectionMapper.complete(91L, "FAILED")).thenReturn(1);
    }

    @Test
    void writesScriptOnceWhenSuccessCallbackIsRepeated() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1, 0);
        when(episodeMapper.completeScriptProjection(22L, "雨夜来客", "完整剧本正文"))
                .thenReturn(1);
        ObjectNode context = objectMapper.createObjectNode();
        context.set("script-normalize", objectMapper.createObjectNode()
                .put("handlerKey", "comic.script")
                .set("script", objectMapper.createObjectNode()
                        .put("title", "雨夜来客")
                        .put("screenplay", "完整剧本正文")));

        projector.projectSucceeded(91L, context);
        projector.projectSucceeded(91L, context);

        verify(episodeMapper).completeScriptProjection(22L, "雨夜来客", "完整剧本正文");
        verify(projectionMapper).complete(91L, "SUCCESS");
        verify(characterMapper, never()).insert(any(ComicCharacter.class));
        verify(sceneMapper, never()).insert(any(ComicScene.class));
    }

    @Test
    void createsDraftCharacterAndSceneVersionsFromGeneratedScriptOnce() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1, 0);
        when(episodeMapper.completeScriptProjection(22L, "Courtyard Secret", "Complete screenplay body"))
                .thenReturn(1);
        stubDraftAssetInserts();
        ObjectNode context = (ObjectNode) objectMapper.valueToTree(Map.of(
                "script", Map.of(
                        "handlerKey", "comic.script",
                        "script", Map.of(
                                "title", "Courtyard Secret",
                                "screenplay", "Complete screenplay body",
                                "characters", List.of(Map.of(
                                        "name", " Hero ",
                                        "appearance", "Short black hair and a blue coat",
                                        "personality", "Calm but determined"
                                )),
                                "locations", List.of(Map.of(
                                        "name", "Old Courtyard",
                                        "description", "Weathered brick walls under cold moonlight"
                                ))
                        )
                )
        ));

        projector.projectSucceeded(91L, context);
        projector.projectSucceeded(91L, context);

        ArgumentCaptor<ComicCharacter> character = ArgumentCaptor.forClass(ComicCharacter.class);
        verify(characterMapper).insert(character.capture());
        assertThat(character.getValue().getName()).isEqualTo("Hero");
        assertThat(character.getValue().getDescription()).contains("Short black hair", "Calm but determined");
        ArgumentCaptor<ComicCharacterVersion> characterVersion =
                ArgumentCaptor.forClass(ComicCharacterVersion.class);
        verify(characterVersionMapper).insert(characterVersion.capture());
        assertThat(characterVersion.getValue().getCharacterId()).isEqualTo(51L);
        assertThat(characterVersion.getValue().getVersionNo()).isEqualTo(1);
        assertThat(characterVersion.getValue().getStatus()).isEqualTo("DRAFT");

        ArgumentCaptor<ComicScene> scene = ArgumentCaptor.forClass(ComicScene.class);
        verify(sceneMapper).insert(scene.capture());
        assertThat(scene.getValue().getName()).isEqualTo("Old Courtyard");
        ArgumentCaptor<ComicSceneVersion> sceneVersion = ArgumentCaptor.forClass(ComicSceneVersion.class);
        verify(sceneVersionMapper).insert(sceneVersion.capture());
        assertThat(sceneVersion.getValue().getSceneId()).isEqualTo(61L);
        assertThat(sceneVersion.getValue().getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void reusesNormalizedAssetsAndDoesNotAddVersionWhenGenerationIsInProgress() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1);
        when(episodeMapper.completeScriptProjection(22L, "Story", "Complete screenplay body")).thenReturn(1);
        ComicCharacter existing = new ComicCharacter();
        existing.setId(51L);
        when(characterMapper.selectByNormalizedNameForUpdate(11L, "hero"))
                .thenReturn(existing);
        when(characterVersionMapper.countReusable(51L)).thenReturn(1);
        ObjectNode context = (ObjectNode) objectMapper.valueToTree(Map.of(
                "script", Map.of(
                        "handlerKey", "comic.script",
                        "script", Map.of(
                                "title", "Story",
                                "screenplay", "Complete screenplay body",
                                "characters", List.of(
                                        Map.of("name", "Hero", "appearance", "Short black hair and a blue coat"),
                                        Map.of("name", " H e r o ", "description", "A longer duplicate description that must not create another asset")
                                )
                        )
                )
        ));

        projector.projectSucceeded(91L, context);

        verify(characterMapper).selectByNormalizedNameForUpdate(11L, "hero");
        verify(characterMapper, never()).insert(any(ComicCharacter.class));
        verify(characterVersionMapper, never()).insert(any(ComicCharacterVersion.class));
    }

    @Test
    void usesNamedStoryboardReferencesWhenImportedScriptHasNoAssetStructure() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1);
        when(episodeMapper.completeStoryboardProjection(22L)).thenReturn(1);
        stubDraftAssetInserts();
        Map<String, Object> fallbackShot = new LinkedHashMap<>(
                shot("shot-1", 0, 5000, "A hero enters the yard")
        );
        fallbackShot.put("references", Map.of(
                "characters", List.of(Map.of(
                        "name", "少年",
                        "description", "少年"
                )),
                "scenes", List.of(Map.of(
                        "name", "古宅",
                        "description", "古宅"
                )),
                "characterVersionIds", List.of("character-unknown:v1"),
                "sceneVersionIds", List.of("scene-unknown:v1")
        ));
        ObjectNode context = (ObjectNode) objectMapper.valueToTree(Map.of(
                "storyboard", Map.of(
                        "handlerKey", "comic.storyboard",
                        "shots", List.of(fallbackShot)
                )
        ));

        projector.projectSucceeded(91L, context);

        ArgumentCaptor<ComicCharacterVersion> characterVersion =
                ArgumentCaptor.forClass(ComicCharacterVersion.class);
        verify(characterVersionMapper).insert(characterVersion.capture());
        assertThat(characterVersion.getValue().getVisualPrompt()).isEqualTo("角色设定：少年");
        ArgumentCaptor<ComicSceneVersion> sceneVersion = ArgumentCaptor.forClass(ComicSceneVersion.class);
        verify(sceneVersionMapper).insert(sceneVersion.capture());
        assertThat(sceneVersion.getValue().getVisualPrompt()).isEqualTo("场景设定：古宅");
    }

    @Test
    void replacesStoryboardWithNormalizedShotRows() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1);
        when(episodeMapper.completeStoryboardProjection(22L)).thenReturn(1);
        ObjectNode context = (ObjectNode) objectMapper.valueToTree(java.util.Map.of(
                "storyboard", java.util.Map.of(
                        "handlerKey", "comic.storyboard",
                        "storyboardVersionId", "board-v2",
                        "shots", List.of(
                                shot("shot-1", 0, 5000, "院门打开"),
                                shot("shot-2", 5000, 11000, "少年走入庭院")
                        )
                )
        ));

        projector.projectSucceeded(91L, context);

        verify(shotMapper).deleteByEpisode(22L);
        ArgumentCaptor<ComicShot> shots = ArgumentCaptor.forClass(ComicShot.class);
        verify(shotMapper, times(2)).insert(shots.capture());
        assertThat(shots.getAllValues()).extracting(ComicShot::getSequenceNo).containsExactly(1, 2);
        assertThat(shots.getAllValues()).extracting(ComicShot::getDurationMs).containsExactly(5000, 6000);
        assertThat(shots.getAllValues()).extracting(ComicShot::getVisualDescription)
                .containsExactly("院门打开", "少年走入庭院");
        verify(episodeMapper).completeStoryboardProjection(22L);
    }

    @Test
    void projectsCharacterBoardToTheVersionPinnedByRunInput() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1);
        when(workflowMapper.selectById(91L)).thenReturn(assetRun(
                "CHARACTER", "character", 41L, 31L, "comic.character_reference"
        ));
        ComicCharacterVersion characterVersion = new ComicCharacterVersion();
        characterVersion.setId(31L);
        characterVersion.setCharacterId(41L);
        characterVersion.setStatus("GENERATING");
        ComicCharacter character = new ComicCharacter();
        character.setId(41L);
        character.setProjectId(11L);
        when(characterVersionMapper.selectById(31L)).thenReturn(characterVersion);
        when(characterMapper.selectById(41L)).thenReturn(character);
        when(characterVersionMapper.markReady(31L, "https://cdn/board.png", "https://cdn/board.png",
                "https://cdn/board.png")).thenReturn(1);
        ObjectNode context = (ObjectNode) objectMapper.valueToTree(java.util.Map.of(
                "character-assets", reference(
                        "comic.character_reference", "31", "three_view_board", "https://cdn/board.png"
                )
        ));

        projector.projectSucceeded(91L, context);

        verify(characterVersionMapper).markReady(
                31L, "https://cdn/board.png", "https://cdn/board.png", "https://cdn/board.png"
        );
    }

    @Test
    void projectsSceneAnchorToTheVersionPinnedByRunInput() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1);
        when(workflowMapper.selectById(91L)).thenReturn(assetRun(
                "SCENE", "scene", 42L, 32L, "comic.scene_reference"
        ));
        ComicSceneVersion sceneVersion = new ComicSceneVersion();
        sceneVersion.setId(32L);
        sceneVersion.setSceneId(42L);
        sceneVersion.setStatus("GENERATING");
        ComicScene scene = new ComicScene();
        scene.setId(42L);
        scene.setProjectId(11L);
        when(sceneVersionMapper.selectById(32L)).thenReturn(sceneVersion);
        when(sceneMapper.selectById(42L)).thenReturn(scene);
        when(sceneVersionMapper.markReady(32L, "https://cdn/scene.png")).thenReturn(1);
        ObjectNode context = (ObjectNode) objectMapper.valueToTree(java.util.Map.of(
                "scene-assets", reference("comic.scene_reference", "32", "scene_anchor_board", "https://cdn/scene.png")
        ));

        projector.projectSucceeded(91L, context);

        verify(sceneVersionMapper).markReady(32L, "https://cdn/scene.png");
    }

    @Test
    void rejectsCharacterOutputForAnotherVersion() {
        when(projectionMapper.claim(91L, "SUCCESS")).thenReturn(1);
        when(workflowMapper.selectById(91L)).thenReturn(assetRun(
                "CHARACTER", "character", 41L, 31L, "comic.character_reference"
        ));
        ObjectNode context = (ObjectNode) objectMapper.valueToTree(java.util.Map.of(
                "character-assets", reference(
                        "comic.character_reference", "99", "three_view_board", "https://cdn/wrong.png"
                )
        ));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> projector.projectSucceeded(91L, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another asset version");

        verify(characterVersionMapper, never()).markReady(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void failedRunRestoresGeneratingEpisodeAndIsIdempotent() {
        when(projectionMapper.claim(91L, "FAILED")).thenReturn(1, 0);
        WorkflowRun run = new WorkflowRun();
        run.setId(91L);
        run.setInputJson("{\"comicAssetType\":\"CHARACTER\",\"comicAssetVersionId\":31}");
        when(workflowMapper.selectById(91L)).thenReturn(run);
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setCharacterId(41L);
        ComicCharacter character = new ComicCharacter();
        character.setProjectId(11L);
        when(characterVersionMapper.selectById(31L)).thenReturn(version);
        when(characterMapper.selectById(41L)).thenReturn(character);

        projector.projectFailed(91L);
        projector.projectFailed(91L);

        verify(episodeMapper).resetGenerationStatus(22L);
        verify(characterVersionMapper).markFailed(31L);
        verify(projectionMapper).complete(91L, "FAILED");
    }

    private void stubDraftAssetInserts() {
        when(characterMapper.insert(any(ComicCharacter.class))).thenAnswer(invocation -> {
            ComicCharacter value = invocation.getArgument(0);
            value.setId(51L);
            return 1;
        });
        when(characterVersionMapper.insert(any(ComicCharacterVersion.class))).thenReturn(1);
        when(sceneMapper.insert(any(ComicScene.class))).thenAnswer(invocation -> {
            ComicScene value = invocation.getArgument(0);
            value.setId(61L);
            return 1;
        });
        when(sceneVersionMapper.insert(any(ComicSceneVersion.class))).thenReturn(1);
    }

    private ComicProjectWorkflowRun binding() {
        ComicProjectWorkflowRun binding = new ComicProjectWorkflowRun();
        binding.setUserId(9L);
        binding.setProjectId(11L);
        binding.setEpisodeId(22L);
        binding.setWorkflowRunId(91L);
        binding.setRootTaskId(92L);
        binding.setLaunchSource("COMIC_PROJECT");
        return binding;
    }

    private WorkflowRun assetRun(String assetType, String assetField, Long assetId,
                                 Long versionId, String handlerKey) {
        WorkflowRun run = new WorkflowRun();
        run.setId(91L);
        run.setUserId(9L);
        run.setRootTaskId(92L);
        run.setLaunchSource("COMIC_PROJECT");
        run.setInputJson("{\"comicProjectId\":11,\"projectId\":11"
                + ",\"comicAssetType\":\"" + assetType + "\""
                + ",\"comicAssetVersionId\":" + versionId
                + ",\"operationHandlerKeys\":[\"" + handlerKey + "\"]"
                + ",\"" + assetField + "\":{\"assetId\":\"" + assetId
                + "\",\"assetVersionId\":\"" + versionId + "\"}}");
        return run;
    }

    private java.util.Map<String, Object> shot(String id, int startMs, int endMs, String description) {
        return java.util.Map.of(
                "shotId", id,
                "timecode", java.util.Map.of("startMs", startMs, "endMs", endMs, "targetDurationMs", endMs - startMs),
                "camera", java.util.Map.of("shotSize", "中景", "angle", "平视", "movement", "固定"),
                "performance", java.util.Map.of("emotion", "紧张"),
                "visualDescription", description,
                "audio", java.util.Map.of("dialogue", "台词", "sfx", List.of("雨声"), "bgmMood", "悬疑"),
                "prompts", java.util.Map.of("image", description, "video", description, "negative", "水印")
        );
    }

    private java.util.Map<String, Object> reference(String handler, String versionId, String role, String url) {
        return java.util.Map.of(
                "handlerKey", handler,
                "referenceAssetVersion", java.util.Map.of(
                        "assetVersionId", versionId,
                        "views", List.of(java.util.Map.of("role", role, "url", url))
                )
        );
    }
}
