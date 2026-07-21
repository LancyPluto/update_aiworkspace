package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectWorkflowRunMapper;
import com.aiminilab.aitoolmarket.comic.service.ComicBatchDispatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectService;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowEpisodeStateService;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowResultProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:comic_workflow_projection_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.task.scheduling.enabled=false"
})
class ComicWorkflowProjectionIntegrationTest {
    @Autowired
    private ComicProjectService projectService;
    @Autowired
    private ComicWorkflowEpisodeStateService stateService;
    @Autowired
    private ComicWorkflowResultProjector projector;
    @Autowired
    private ComicProjectWorkflowRunMapper workflowRunMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ComicBatchDispatchService dispatchService;

    @BeforeEach
    void clean() {
        for (String table : List.of(
                "comic_workflow_projections", "comic_project_workflow_runs", "comic_shots",
                "comic_character_versions", "comic_scene_versions", "comic_characters", "comic_scenes",
                "comic_episodes", "comic_projects")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    @Test
    void scriptProjectionTransitionsGeneratingEpisodeAndPersistsReceiptOnce() {
        long userId = 9L;
        ComicDtos.ProjectDetail project = projectService.createProject(userId,
                new ComicDtos.CreateProjectRequest("古城秘密", null, "16:9", "国风动画"));
        var snapshot = stateService.startScriptGeneration(
                userId,
                project.id(),
                new ComicDtos.GenerateEpisodeRequest(
                        "雨夜来客", "少年发现古城秘密", 1, "projection-script-request"
                )
        );
        ComicProjectWorkflowRun binding = new ComicProjectWorkflowRun();
        binding.setUserId(userId);
        binding.setProjectId(project.id());
        binding.setEpisodeId(snapshot.episode().getId());
        binding.setWorkflowRunId(91L);
        binding.setRootTaskId(92L);
        binding.setLaunchSource("COMIC_PROJECT");
        binding.setCreatedAt(LocalDateTime.now());
        workflowRunMapper.insert(binding);
        ObjectNode context = objectMapper.createObjectNode();
        ObjectNode script = objectMapper.createObjectNode()
                .put("title", "雨夜来客")
                .put("screenplay", "第一幕：雨夜，少年推开古城大门。");
        script.putArray("characters").addObject()
                .put("name", "少年")
                .put("appearance", "黑色短发，身穿深蓝色旅行外套");
        script.putArray("locations").addObject()
                .put("name", "古城大门")
                .put("description", "雨夜中的斑驳石门，冷色灯笼照亮湿润地面");
        context.set("script-normalize", objectMapper.createObjectNode()
                .put("handlerKey", "comic.script")
                .set("script", script));

        projector.projectSucceeded(91L, context);
        projector.projectSucceeded(91L, context);

        ComicDtos.EpisodeDetail episode = projectService.episodeDetail(
                userId, project.id(), snapshot.episode().getId()
        );
        assertThat(episode.status()).isEqualTo("DRAFT");
        assertThat(episode.scriptText()).isEqualTo("第一幕：雨夜，少年推开古城大门。");
        assertThat(episode.revision()).isEqualTo(1L);
        ComicDtos.ProjectDetail firstProjection = projectService.detail(userId, project.id());
        assertThat(firstProjection.characters().get(0).versions().get(0).status()).isEqualTo("DRAFT");
        assertThat(firstProjection.scenes().get(0).versions().get(0).status()).isEqualTo("DRAFT");

        jdbcTemplate.update("UPDATE comic_character_versions SET status = 'GENERATING'");
        jdbcTemplate.update("UPDATE comic_scene_versions SET status = 'GENERATING'");
        var secondSnapshot = stateService.startScriptGeneration(
                userId,
                project.id(),
                new ComicDtos.GenerateEpisodeRequest(
                        "雨夜续篇", "少年继续调查古城秘密", 2, "projection-script-request-2"
                )
        );
        ComicProjectWorkflowRun secondBinding = new ComicProjectWorkflowRun();
        secondBinding.setUserId(userId);
        secondBinding.setProjectId(project.id());
        secondBinding.setEpisodeId(secondSnapshot.episode().getId());
        secondBinding.setWorkflowRunId(93L);
        secondBinding.setRootTaskId(94L);
        secondBinding.setLaunchSource("COMIC_PROJECT");
        secondBinding.setCreatedAt(LocalDateTime.now());
        workflowRunMapper.insert(secondBinding);
        projector.projectSucceeded(93L, context);

        ComicDtos.ProjectDetail projected = projectService.detail(userId, project.id());
        assertThat(projected.characters()).singleElement().satisfies(character -> {
            assertThat(character.name()).isEqualTo("少年");
            assertThat(character.versions()).singleElement()
                    .satisfies(version -> assertThat(version.status()).isEqualTo("GENERATING"));
        });
        assertThat(projected.scenes()).singleElement().satisfies(scene -> {
            assertThat(scene.name()).isEqualTo("古城大门");
            assertThat(scene.versions()).singleElement()
                    .satisfies(version -> assertThat(version.status()).isEqualTo("GENERATING"));
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comic_workflow_projections WHERE workflow_run_id = 91",
                Integer.class
        )).isEqualTo(1);
    }
}
