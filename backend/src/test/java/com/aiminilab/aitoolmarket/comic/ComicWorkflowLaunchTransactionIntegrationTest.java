package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.service.ComicBatchDispatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectService;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowLaunchService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:comic_workflow_launch_tx_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.task.scheduling.enabled=false"
})
class ComicWorkflowLaunchTransactionIntegrationTest {
    @Autowired
    private ComicProjectService projectService;
    @Autowired
    private ComicWorkflowLaunchService launchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private WorkflowRunApplicationService workflowRunApplicationService;
    @MockBean
    private ComicBatchDispatchService dispatchService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM comic_episodes");
        jdbcTemplate.update("DELETE FROM comic_projects");
    }

    @Test
    void workflowCreationFailureRollsBackTheScriptPlaceholder() {
        long userId = 9L;
        ComicDtos.ProjectDetail project = projectService.createProject(
                userId,
                new ComicDtos.CreateProjectRequest("古城秘密", null, "16:9", "国风动画")
        );
        when(workflowRunApplicationService.createInCurrentTransaction(any()))
                .thenThrow(new IllegalStateException("workflow unavailable"));

        assertThatThrownBy(() -> launchService.generateScript(
                userId,
                project.id(),
                new ComicDtos.GenerateEpisodeRequest(
                        "雨夜来客", "少年发现古城秘密", 1, "rollback-request"
                )
        )).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comic_episodes WHERE project_id = ?",
                Integer.class,
                project.id()
        )).isZero();
    }
}
