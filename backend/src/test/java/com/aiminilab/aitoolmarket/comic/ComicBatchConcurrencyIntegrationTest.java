package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.service.ComicBatchAttemptLaunchService;
import com.aiminilab.aitoolmarket.comic.service.ComicBatchDispatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicGenerationBatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:comic_batch_concurrency_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.task.scheduling.enabled=false"
})
class ComicBatchConcurrencyIntegrationTest {
    @Autowired
    private ComicGenerationBatchService batchService;

    @Autowired
    private ComicBatchAttemptLaunchService launchService;

    @Autowired
    private ComicBatchDispatchService dispatchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowRunApplicationService workflowRunApplicationService;

    @MockBean
    private ComicProjectService projectService;

    @BeforeEach
    void clean() {
        for (String table : List.of(
                "comic_workflow_projections", "comic_shot_attempts", "comic_generation_batches",
                "comic_shots", "comic_episodes", "comic_projects")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    @Test
    void episodeLockAllowsOnlyOneActiveBatchAcrossDifferentRequestIds() throws Exception {
        insertProjectAndEpisode("ASSETS_CONFIRMED");
        insertShot(101L, 1, null);
        insertShot(102L, 2, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> createBatchAfterSignal("concurrent-a", 101L, ready, start)),
                    executor.submit(() -> createBatchAfterSignal("concurrent-b", 102L, ready, start))
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(getOutcome(future));
            }
            assertThat(outcomes.stream().filter(ComicDtos.BatchDetail.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(BusinessException.class::isInstance)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM comic_generation_batches WHERE episode_id = 20 AND status IN ('CREATED','RUNNING')",
                    Integer.class
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void batchRowLockKeepsConcurrentLaunchesWithinParallelism() throws Exception {
        insertProjectAndEpisode("GENERATING");
        insertBatch(41L, "RUNNING", 4, "launch-cap");
        for (long id = 101; id <= 108; id++) {
            insertShot(id, (int) (id - 100), null);
            insertAttempt(id + 1000, 41L, id, 1, "PENDING", null);
        }
        when(projectService.buildShotOperationInput(anyLong(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> objectMapper.createObjectNode());
        AtomicLong sequence = new AtomicLong(5000);
        when(workflowRunApplicationService.createInCurrentTransaction(any()))
                .thenAnswer(invocation -> {
                    long value = sequence.incrementAndGet();
                    return new WorkflowRunCreated(value + 1000, value, value + 2000, "RUNNING");
                });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> launchUntilBlocked(41L, start));
            Future<Integer> second = executor.submit(() -> launchUntilBlocked(41L, start));
            start.countDown();
            int launched = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);

            assertThat(launched).isEqualTo(4);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM comic_shot_attempts WHERE batch_id = 41 AND status = 'RUNNING'",
                    Integer.class
            )).isEqualTo(4);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM comic_shot_attempts WHERE batch_id = 41 AND status = 'PENDING'",
                    Integer.class
            )).isEqualTo(4);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void bindFailureRollsBackLaunchSideEffectsBeforeAttemptIsFailed() {
        insertProjectAndEpisode("GENERATING");
        insertBatch(41L, "RUNNING", 1, "rollback-launch");
        insertShot(101L, 1, null);
        insertShot(102L, 2, null);
        insertAttempt(1101L, 41L, 101L, 1, "SUCCESS", 9001L);
        insertAttempt(1102L, 41L, 102L, 1, "PENDING", null);
        when(projectService.buildShotOperationInput(anyLong(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> objectMapper.createObjectNode());
        when(workflowRunApplicationService.createInCurrentTransaction(any()))
                .thenAnswer(invocation -> {
                    jdbcTemplate.update("""
                            INSERT INTO comic_workflow_projections(workflow_run_id, projection_type, status)
                            VALUES (7777, 'TEST', 'PROJECTING')
                            """);
                    return new WorkflowRunCreated(9901L, 9001L, 9902L, "RUNNING");
                });

        ComicBatchAttemptLaunchService.AttemptLaunchException failure = null;
        try {
            launchService.launchNext(41L);
        } catch (ComicBatchAttemptLaunchService.AttemptLaunchException exception) {
            failure = exception;
        }

        assertThat(failure).isNotNull();
        assertThat(failure.attemptId()).isEqualTo(1102L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM comic_shot_attempts WHERE id = 1102", String.class
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comic_workflow_projections WHERE workflow_run_id = 7777", Integer.class
        )).isZero();

        assertThat(launchService.failAfterRollback(failure)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM comic_shot_attempts WHERE id = 1102", String.class
        )).isEqualTo("FAILED");
    }

    @Test
    void retryDependingOnFailedAttemptFromOlderBatchTerminates() {
        insertProjectAndEpisode("GENERATING");
        insertShot(101L, 1, null);
        insertShot(102L, 2, 101L);
        insertBatch(40L, "FAILED", 1, "old-dependency");
        insertAttempt(1101L, 40L, 101L, 1, "FAILED", null);
        insertBatch(41L, "CREATED", 1, "retry-dependent");
        insertAttempt(1102L, 41L, 102L, 1, "PENDING", null);

        ComicDtos.BatchDetail detail = dispatchService.dispatch(1L, 10L, 20L, 41L);

        assertThat(detail.status()).isEqualTo("FAILED");
        assertThat(detail.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo("FAILED");
            assertThat(attempt.errorCode()).isEqualTo("DEPENDENCY_FAILED");
        });
    }

    @Test
    void cancellingAttemptBlocksOverlappingShotRetry() {
        insertProjectAndEpisode("GENERATING");
        insertShot(101L, 1, null);
        insertBatch(40L, "FAILED", 1, "cancelling-attempt");
        insertAttempt(1101L, 40L, 101L, 1, "CANCELLING", 9001L);

        assertThatThrownBy(() -> batchService.createRetry(
                1L, 10L, 20L, 101L,
                new ComicDtos.RetryShotRequest("retry-while-cancelling", null)
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在运行");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comic_generation_batches", Integer.class
        )).isEqualTo(1);
    }

    private Object createBatchAfterSignal(String requestId, Long shotId,
                                          CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        try {
            return batchService.create(
                    1L, 10L, 20L,
                    new ComicDtos.CreateBatchRequest(requestId, null, 1, null, true, List.of(shotId))
            );
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private Object getOutcome(Future<Object> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private int launchUntilBlocked(Long batchId, CountDownLatch start) {
        await(start);
        int launched = 0;
        for (int index = 0; index < 8; index++) {
            ComicBatchAttemptLaunchService.LaunchResult result = launchService.launchNext(batchId);
            if (result == ComicBatchAttemptLaunchService.LaunchResult.LAUNCHED) {
                launched++;
                continue;
            }
            if (result == ComicBatchAttemptLaunchService.LaunchResult.RETRY) {
                continue;
            }
            break;
        }
        return launched;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrent test signal");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void insertProjectAndEpisode(String episodeStatus) {
        jdbcTemplate.update("""
                INSERT INTO comic_projects(id, user_id, title, aspect_ratio, status, revision)
                VALUES (10, 1, 'Comic', '16:9', 'ACTIVE', 0)
                """);
        jdbcTemplate.update("""
                INSERT INTO comic_episodes(
                    id, project_id, episode_no, title, script_source_type, script_text, status, revision
                ) VALUES (20, 10, 1, 'Episode', 'PASTE', 'Script', ?, 0)
                """, episodeStatus);
    }

    private void insertShot(Long id, int sequence, Long dependencyId) {
        jdbcTemplate.update("""
                INSERT INTO comic_shots(
                    id, episode_id, shot_key, sequence_no, duration_ms, visual_description,
                    character_version_ids_json, depends_on_shot_id, status, revision
                ) VALUES (?, 20, ?, ?, 5000, 'Shot', '[]', ?, 'DRAFT', 0)
                """, id, "00000000-0000-0000-0000-%012d".formatted(id), sequence, dependencyId);
    }

    private void insertBatch(Long id, String status, int maxParallelism, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO comic_generation_batches(
                    id, user_id, project_id, episode_id, batch_type, tool_code, client_request_id,
                    max_parallelism, status, request_json, confirmed_at
                ) VALUES (?, 1, 10, 20, 'SHOT_VIDEO', 'ai_comic_drama_agent', ?, ?, ?, '{}', CURRENT_TIMESTAMP)
                """, id, requestId, maxParallelism, status);
    }

    private void insertAttempt(Long id, Long batchId, Long shotId, int attemptNo,
                               String status, Long workflowRunId) {
        jdbcTemplate.update("""
                INSERT INTO comic_shot_attempts(
                    id, batch_id, shot_id, attempt_no, idempotency_key, workflow_run_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, batchId, shotId, attemptNo, "attempt-" + id, workflowRunId, status);
    }
}
