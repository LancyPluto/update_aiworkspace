package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicAssemblyBatch;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.aiminilab.aitoolmarket.comic.mapper.ComicAssemblyBatchMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotAttemptMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.comic.service.ComicAssemblyBatchService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComicAssemblyBatchServiceTest {
    @Mock
    private ComicAssemblyBatchMapper batchMapper;
    @Mock
    private ComicEpisodeMapper episodeMapper;
    @Mock
    private ComicShotMapper shotMapper;
    @Mock
    private ComicShotAttemptMapper attemptMapper;
    @Mock
    private WorkflowRunMapper workflowRunMapper;
    @Mock
    private WorkflowRunStepMapper workflowRunStepMapper;
    @Mock
    private WorkflowRunApplicationService workflowRunApplicationService;

    private ObjectMapper objectMapper;
    private ComicAssemblyBatchService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ComicAssemblyBatchService(
                batchMapper, episodeMapper, shotMapper, attemptMapper,
                workflowRunMapper, workflowRunStepMapper, workflowRunApplicationService, objectMapper
        );
    }

    @Test
    void freezesSelectedAttemptsAndCreatesOnlyComposeRunInCurrentTransaction() throws Exception {
        ComicEpisode episode = episode(20L, "GENERATING");
        ComicShot first = shot(101L, 1, 1001L, "第一句");
        ComicShot second = shot(102L, 2, 1002L, "第二句");
        when(episodeMapper.selectOwnedForUpdate(10L, 20L, 7L)).thenReturn(episode);
        when(batchMapper.selectActiveByEpisode(20L)).thenReturn(null);
        when(shotMapper.selectByEpisode(20L)).thenReturn(List.of(first, second));
        when(attemptMapper.selectForShot(1001L, 101L)).thenReturn(attempt(
                1001L, 101L, result("shot-1:v4", "clip-1:v9", "/clips/one.mp4", "audio-1:v2", "/audio/one.mp3")
        ));
        when(attemptMapper.selectForShot(1002L, 102L)).thenReturn(attempt(
                1002L, 102L, result("shot-2:v3", "clip-2:v7", "/clips/two.mp4", null, null)
        ));
        doAnswer(invocation -> {
            ComicAssemblyBatch batch = invocation.getArgument(0);
            batch.setId(41L);
            return 1;
        }).when(batchMapper).insert(any(ComicAssemblyBatch.class));
        when(workflowRunApplicationService.createInCurrentTransaction(any()))
                .thenReturn(new WorkflowRunCreated(601L, 501L, 301L, "RUNNING"));
        when(batchMapper.bindWorkflow(eq(41L), eq(501L), eq(601L), eq("RUNNING"), any()))
                .thenReturn(1);
        WorkflowRun running = new WorkflowRun();
        running.setId(501L);
        running.setStatus("RUNNING");
        when(workflowRunMapper.selectById(501L)).thenReturn(running);

        ComicDtos.AssemblyBatchDetail detail = service.create(
                7L, 10L, 20L,
                new ComicDtos.CreateAssemblyBatchRequest("assemble-request-1", true)
        );

        assertThat(detail.status()).isEqualTo("RUNNING");
        assertThat(detail.shotCount()).isEqualTo(2);
        assertThat(detail.selectedAttemptIds()).containsExactly(1001L, 1002L);
        ArgumentCaptor<CreateWorkflowRunCommand> command = ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(workflowRunApplicationService).createInCurrentTransaction(command.capture());
        assertThat(command.getValue().clientRequestId()).isEqualTo("comic:assembly:41");
        assertThat(command.getValue().launchSource()).isEqualTo("COMIC_PROJECT");
        JsonNode input = command.getValue().input();
        assertThat(input.path("operationHandlerKey").asText()).isEqualTo("comic.compose");
        assertThat(input.path("compositionVersionId").asText()).isEqualTo("comic:assembly:41");
        assertThat(input.path("selectedShotVersions")).hasSize(2);
        assertThat(input.path("selectedShotVersions").get(0).path("clipVersionId").asText())
                .isEqualTo("clip-1:v9");
        assertThat(input.path("selectedShotVersions").get(1).path("order").asInt()).isEqualTo(2);
    }

    @Test
    void rejectsAssemblyWhenSelectedAttemptHasNoVideoArtifact() {
        ComicEpisode episode = episode(20L, "GENERATING");
        ComicShot shot = shot(101L, 1, 1001L, "第一句");
        when(episodeMapper.selectOwnedForUpdate(10L, 20L, 7L)).thenReturn(episode);
        when(batchMapper.selectActiveByEpisode(20L)).thenReturn(null);
        when(shotMapper.selectByEpisode(20L)).thenReturn(List.of(shot));
        when(attemptMapper.selectForShot(1001L, 101L)).thenReturn(attempt(
                1001L, 101L, "{\"shot-video\":{\"handlerKey\":\"comic.shot_video\"}}"
        ));

        assertThatThrownBy(() -> service.create(
                7L, 10L, 20L,
                new ComicDtos.CreateAssemblyBatchRequest("assemble-request-2", true)
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("视频产物");
        verify(workflowRunApplicationService, never()).createInCurrentTransaction(any());
    }

    @Test
    void reconcilesStructuredComposeOutputToMp4AndEpisodeSrt() {
        ComicAssemblyBatch batch = new ComicAssemblyBatch();
        batch.setId(41L);
        batch.setUserId(7L);
        batch.setProjectId(10L);
        batch.setEpisodeId(20L);
        batch.setToolCode("ai-comic-drama");
        batch.setClientRequestId("assemble-request-3");
        batch.setShotCount(2);
        batch.setSelectedShotsJson("[{\"selectedAttemptId\":1001},{\"selectedAttemptId\":1002}]");
        batch.setStatus("RUNNING");
        batch.setWorkflowRunId(501L);
        when(batchMapper.selectOwned(41L, 7L)).thenReturn(batch);

        WorkflowRun run = new WorkflowRun();
        run.setId(501L);
        run.setStatus("SUCCESS");
        run.setFinishedAt(LocalDateTime.of(2026, 7, 20, 12, 0));
        when(workflowRunMapper.selectById(501L)).thenReturn(run);
        WorkflowRunStep step = new WorkflowRunStep();
        step.setStatus("SUCCESS");
        step.setOutputJson("""
                {"handlerKey":"comic.compose","finalVideoUrl":"/final/comic.mp4",
                 "subtitleUrl":"/final/comic-final.srt",
                 "compositionManifest":{"compositionVersionId":"comic:assembly:41"}}
                """);
        when(workflowRunStepMapper.selectByRunIdAndNodeId(501L, "compose")).thenReturn(step);
        when(batchMapper.completeSuccess(
                eq(41L), any(), eq("/final/comic.mp4"), eq("/final/comic-final.srt"), any()
        )).thenReturn(1);

        ComicDtos.AssemblyBatchDetail detail = service.get(7L, 10L, 20L, 41L);

        assertThat(detail.status()).isEqualTo("SUCCESS");
        assertThat(detail.finalVideoUrl()).isEqualTo("/final/comic.mp4");
        assertThat(detail.subtitleUrl()).isEqualTo("/final/comic-final.srt");
        verify(batchMapper).markEpisodeCompleted(20L);
    }

    private ComicEpisode episode(Long id, String status) {
        ComicEpisode episode = new ComicEpisode();
        episode.setId(id);
        episode.setProjectId(10L);
        episode.setTitle("第一集");
        episode.setStatus(status);
        return episode;
    }

    private ComicShot shot(Long id, int order, Long selectedAttemptId, String dialogue) {
        ComicShot shot = new ComicShot();
        shot.setId(id);
        shot.setSequenceNo(order);
        shot.setSelectedAttemptId(selectedAttemptId);
        shot.setDialogue(dialogue);
        shot.setRevision(3L);
        return shot;
    }

    private ComicShotAttempt attempt(Long id, Long shotId, String resultJson) {
        ComicShotAttempt attempt = new ComicShotAttempt();
        attempt.setId(id);
        attempt.setShotId(shotId);
        attempt.setStatus("SUCCESS");
        attempt.setResultJson(resultJson);
        return attempt;
    }

    private String result(String shotVersionId, String clipVersionId, String videoUrl,
                          String audioVersionId, String audioUrl) throws Exception {
        var root = objectMapper.createObjectNode();
        var video = root.putObject("shot-video");
        video.put("handlerKey", "comic.shot_video");
        video.put("shotVersionId", shotVersionId);
        var clip = video.putObject("clipVersion");
        clip.put("shotVersionId", shotVersionId);
        clip.put("clipVersionId", clipVersionId);
        clip.put("videoUrl", videoUrl);
        if (audioVersionId != null) {
            var audio = root.putObject("shot-tts");
            audio.put("handlerKey", "comic.shot_tts");
            var version = audio.putObject("audioVersion");
            version.put("audioVersionId", audioVersionId);
            version.put("audioUrl", audioUrl);
        }
        return objectMapper.writeValueAsString(root);
    }
}
