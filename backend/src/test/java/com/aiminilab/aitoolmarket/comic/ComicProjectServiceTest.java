package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.service.ComicBatchDispatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicGenerationBatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectApplicationService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:comic_project_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.task.scheduling.enabled=false"
})
class ComicProjectServiceTest {
    @Autowired
    private ComicProjectService projectService;

    @Autowired
    private ComicGenerationBatchService batchService;

    @Autowired
    private ComicProjectApplicationService applicationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ComicBatchDispatchService dispatchService;

    @BeforeEach
    void cleanComicTables() {
        for (String table : List.of(
                "comic_project_workflow_runs", "comic_shot_attempts", "comic_generation_batches",
                "comic_shots", "comic_character_versions", "comic_characters",
                "comic_scene_versions", "comic_scenes", "comic_episodes", "comic_projects")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    @Test
    void persistsProjectLocksStoryboardConfirmsAssetsAndCreatesIdempotentBatch() {
        long userId = 1L;
        ComicDtos.ProjectDetail project = projectService.createProject(userId,
                new ComicDtos.CreateProjectRequest("测试漫剧", null, "16:9", "国风动画"));
        ComicDtos.EpisodeDetail episode = projectService.createEpisode(userId, project.id(),
                new ComicDtos.CreateEpisodeRequest("第一集", "完整剧本", "PASTE", 1));

        List<ComicDtos.ShotInput> shots = IntStream.rangeClosed(1, 6)
                .mapToObj(this::shot).toList();
        episode = projectService.replaceShots(userId, project.id(), episode.id(),
                new ComicDtos.ReplaceShotsRequest(shots));
        List<Long> stableIds = episode.shots().stream().map(ComicDtos.ShotDetail::id).toList();
        assertThat(episode.revision()).isEqualTo(1);

        episode = projectService.lockStoryboard(userId, project.id(), episode.id(),
                new ComicDtos.RevisionRequest(episode.revision()));
        assertThat(episode.status()).isEqualTo("STORYBOARD_LOCKED");

        ComicDtos.CharacterDetail character = projectService.createCharacter(userId, project.id(),
                new ComicDtos.CreateCharacterRequest("主角", "少年", null));
        ComicDtos.CharacterVersionDetail characterVersion = projectService.createCharacterVersion(
                userId, project.id(), character.id(),
                new ComicDtos.CreateCharacterVersionRequest(
                        "角色三视图", "https://a/front.png", "https://a/side.png",
                        "https://a/back.png", "READY")
        );
        ComicDtos.SceneDetail scene = projectService.createScene(userId, project.id(),
                new ComicDtos.CreateSceneRequest("古城", "夜晚古城"));
        ComicDtos.SceneVersionDetail sceneVersion = projectService.createSceneVersion(
                userId, project.id(), scene.id(),
                new ComicDtos.CreateSceneVersionRequest("场景锚点", "https://a/scene.png", "READY")
        );

        for (Long shotId : stableIds) {
            episode = projectService.updateAssetRefs(
                    userId, project.id(), episode.id(), shotId,
                    new ComicDtos.UpdateAssetRefsRequest(
                            List.of(characterVersion.id()), sceneVersion.id(), episode.revision())
            );
        }
        episode = projectService.confirmAssets(userId, project.id(), episode.id(),
                new ComicDtos.RevisionRequest(episode.revision()));
        assertThat(episode.status()).isEqualTo("ASSETS_CONFIRMED");

        ComicDtos.CreateBatchRequest request = new ComicDtos.CreateBatchRequest(
                "comic-batch-idempotent-1", null, 3, 120, true, null
        );
        ComicDtos.BatchDetail first = batchService.create(userId, project.id(), episode.id(), request);
        ComicDtos.BatchDetail repeated = batchService.create(userId, project.id(), episode.id(), request);

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(first.totalCount()).isEqualTo(6);
        assertThat(first.pendingCount()).isEqualTo(6);
        assertThat(first.attempts()).allMatch(item -> item.attemptNo() == 1);
        assertThat(batchService.requireLatest(userId, project.id(), episode.id()).getId()).isEqualTo(first.id());
        assertThatThrownBy(() -> projectService.detail(2L, project.id()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @Transactional
    void agentWorkflowCreatesAndBindsWorkspaceWithoutControllerCoupling() throws Exception {
        var prepared = applicationService.prepare(
                1L,
                objectMapper.readTree("{\"storyTheme\":\"古城奇遇\",\"plotOutline\":\"完整剧情\"}"),
                "AGENT_CHAT"
        );
        applicationService.bind(prepared, 1L, 991001L, 992001L, "AGENT_CHAT");

        ComicDtos.WorkspaceBinding binding = applicationService.workspaceByRootTaskIdInternal(991001L);
        assertThat(binding.projectId()).isEqualTo(prepared.projectId());
        assertThat(binding.episodeId()).isEqualTo(prepared.episodeId());
        assertThat(binding.workspacePath()).contains("/agents/comic-projects/");
        assertThat(prepared.input().path("comicProjectId").asLong()).isEqualTo(prepared.projectId());
    }

    private ComicDtos.ShotInput shot(int sequence) {
        return new ComicDtos.ShotInput(
                null, null, sequence, 5000, "中景", "平视", "固定", "平静",
                "第 " + sequence + " 个分镜", "对白", null, null, null,
                "首帧", "视频", "水印", List.of(), null, null
        );
    }
}
