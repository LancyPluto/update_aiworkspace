package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import com.aiminilab.aitoolmarket.ppt.engine.EngineCapabilities;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobState;
import com.aiminilab.aitoolmarket.ppt.engine.EngineSubmission;
import com.aiminilab.aitoolmarket.ppt.engine.PptEngineAdapter;
import com.aiminilab.aitoolmarket.ppt.engine.PptEngineRegistry;
import com.aiminilab.aitoolmarket.ppt.entity.PptEngineBinding;
import com.aiminilab.aitoolmarket.ppt.entity.PptJob;
import com.aiminilab.aitoolmarket.ppt.entity.PptProject;
import com.aiminilab.aitoolmarket.ppt.mapper.PptEngineBindingMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptExportMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptJobMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptProjectMapper;
import com.aiminilab.aitoolmarket.ppt.security.PptExecutionTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PptJobApplicationServiceTest {

    @Mock private PptWorkspaceService workspaceService;
    @Mock private PptProjectMapper projectMapper;
    @Mock private PptJobMapper jobMapper;
    @Mock private PptEngineBindingMapper bindingMapper;
    @Mock private PptExportMapper exportMapper;
    @Mock private PptEngineRegistry engineRegistry;
    @Mock private PptPlatformModelBindingService platformModelBindingService;
    @Mock private PptExecutionTokenService executionTokenService;
    @Mock private CreditService creditService;
    @Mock private PptEngineAdapter engineAdapter;

    private PptJobApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PptJobApplicationService(
                workspaceService,
                projectMapper,
                jobMapper,
                bindingMapper,
                exportMapper,
                engineRegistry,
                platformModelBindingService,
                executionTokenService,
                creditService,
                new PptEngineProperties(),
                new ObjectMapper()
        );
        lenient().when(jobMapper.findRecoverable(org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
    }

    @Test
    void recoveryCapturesPendingSettlementExactlyThroughIdempotentCreditApi() {
        PptJob job = pendingCreditJob("SETTLEMENT_PENDING");
        when(jobMapper.findPendingCreditOperations(20)).thenReturn(List.of(job));

        service.recoverJobs();

        verify(creditService).captureReserved(
                10L, CreditSourceType.PPT_STEP, 30L, 50, 50, "ppt-job:30:settle");
        verify(jobMapper).updateCreditState(30L, "SETTLEMENT_PENDING", "SETTLED", 50);
    }

    @Test
    void recoveryReleasesPendingReservationExactlyThroughIdempotentCreditApi() {
        PptJob job = pendingCreditJob("RELEASE_PENDING");
        when(jobMapper.findPendingCreditOperations(20)).thenReturn(List.of(job));

        service.recoverJobs();

        verify(creditService).releaseReserved(
                10L, CreditSourceType.PPT_STEP, 30L, 50, "ppt-job:30:release");
        verify(jobMapper).updateCreditState(30L, "RELEASE_PENDING", "RELEASED", 0);
    }

    @Test
    void leaseContentionDoesNotAdvanceTheJob() {
        when(jobMapper.claimLease(eq(30L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(service, "advanceWithLease", 30L);

        verify(jobMapper, never()).selectById(30L);
    }

    @Test
    void expiredStageFailsThroughTerminalCas() {
        PptJob job = activeJob("RUNNING");
        job.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        when(jobMapper.claimLease(eq(30L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(jobMapper.selectById(30L)).thenReturn(job);

        ReflectionTestUtils.invokeMethod(service, "advanceWithLease", 30L);

        verify(jobMapper).finish(
                eq(30L), eq("FAILED"), anyInt(), anyString(), nullable(String.class),
                eq("PPT_STAGE_TIMEOUT"), anyString(), eq(true), anyInt(), anyString());
    }

    @Test
    void unavailableEngineFailsBeforeCreditsAreFrozen() {
        PptJob job = activeJob("CREATED");
        when(jobMapper.claimLease(eq(30L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(jobMapper.selectById(30L)).thenReturn(job);
        when(engineRegistry.require("BANANA")).thenReturn(engineAdapter);
        when(engineAdapter.capabilities()).thenReturn(new EngineCapabilities(
                "BANANA", "Banana", false, true, true, false, Set.of("GENERATE_IMAGES")));

        ReflectionTestUtils.invokeMethod(service, "advanceWithLease", 30L);

        verify(creditService, never()).tryFreeze(anyLong(), any(), anyLong(), anyInt(), anyString());
        verify(jobMapper).finish(
                eq(30L), eq("FAILED"), anyInt(), anyString(), nullable(String.class),
                eq("PPT_ENGINE_UNAVAILABLE"), anyString(), eq(true), anyInt(), anyString());
    }

    @Test
    void interruptedReceiptIsRedrivenWithTheSamePlatformJob() {
        PptJob job = activeJob("RECONCILING");
        job.setIdempotencyKey("ppt:10:images-1");
        job.setRequestJson("{}");
        job.setReconcileStartedAt(LocalDateTime.now());
        PptProject project = new PptProject();
        project.setId(20L);
        project.setUserId(10L);
        PptEngineBinding binding = new PptEngineBinding();
        binding.setProjectId(20L);
        binding.setEngineCode("BANANA");
        binding.setExternalProjectId("engine-project");
        var interrupted = new ObjectMapper().createObjectNode()
                .put("error_code", "ENGINE_INTERRUPTED")
                .put("retryable", true);

        when(jobMapper.claimLease(eq(30L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(jobMapper.selectById(30L)).thenReturn(job);
        when(bindingMapper.findByProjectAndEngine(20L, "BANANA")).thenReturn(binding);
        when(engineRegistry.require("BANANA")).thenReturn(engineAdapter);
        when(engineAdapter.reconcileSubmission("engine-project", "ppt:10:images-1"))
                .thenReturn(new EngineSubmission(EngineJobState.FAILED, "old-task", 20, "中断", interrupted));
        when(workspaceService.requireProject(10L, 20L)).thenReturn(project);
        when(executionTokenService.issue(eq(20L), eq(30L), any(), anyLong()))
                .thenReturn(new PptExecutionTokenService.IssuedToken("replacement-token", 9999999999L));
        when(engineAdapter.submit(any())).thenReturn(
                new EngineSubmission(EngineJobState.SUBMITTED, "new-task", 20, "已恢复", null));

        ReflectionTestUtils.invokeMethod(service, "advanceWithLease", 30L);

        verify(engineAdapter).submit(any());
        verify(jobMapper).updateActiveState(
                eq(30L), eq("SUBMITTED"), eq("new-task"), eq(20), eq("已恢复"),
                nullable(String.class), any(LocalDateTime.class));
    }

    @Test
    void pendingReservationIsRecoveredIdempotentlyBeforeSubmission() {
        PptJob job = activeJob("CREDIT_RESERVED");
        job.setReservedCredits(50);
        job.setCreditState("RESERVATION_PENDING");
        when(creditService.tryFreeze(
                10L, CreditSourceType.PPT_STEP, 30L, 50, "ppt-job:30:reserve"))
                .thenReturn(true);
        when(jobMapper.updateCreditState(30L, "RESERVATION_PENDING", "RESERVED", 0))
                .thenReturn(1);

        Boolean recovered = ReflectionTestUtils.invokeMethod(service, "ensureCreditReservation", job);

        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, recovered);
        verify(creditService).tryFreeze(
                10L, CreditSourceType.PPT_STEP, 30L, 50, "ppt-job:30:reserve");
        verify(jobMapper).updateCreditState(30L, "RESERVATION_PENDING", "RESERVED", 0);
    }

    @Test
    void normalizesBananaNestedPageFieldsForWorkbenchDeck() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var snapshot = mapper.readTree("""
                {
                  "pages": [{
                    "id": "page-1",
                    "order_index": 0,
                    "outline_content": {"title": "封面", "points": ["主题"]},
                    "description_content": {"text": "完整页面描述"},
                    "generated_image_url": "/files/project-1/pages/page-1.png"
                  }]
                }
                """);

        var deck = ReflectionTestUtils.<com.fasterxml.jackson.databind.node.ObjectNode>invokeMethod(
                service, "normalizeDeck", 20L, snapshot, null);

        assertThat(deck.path("slides").get(0).path("title").asText()).isEqualTo("封面");
        assertThat(deck.path("slides").get(0).path("description").asText()).isEqualTo("完整页面描述");
        assertThat(deck.path("slides").get(0).path("previewUrl").asText())
                .isEqualTo("/api/v2/ppt/projects/20/files/project-1/pages/page-1.png");
    }

    private PptJob pendingCreditJob(String creditState) {
        PptJob job = new PptJob();
        job.setId(30L);
        job.setUserId(10L);
        job.setProjectId(20L);
        job.setReservedCredits(50);
        job.setCreditState(creditState);
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

    private PptJob activeJob(String status) {
        PptJob job = pendingCreditJob("NOT_REQUIRED");
        job.setStatus(status);
        job.setJobType("GENERATE_IMAGES");
        job.setEngineCode("BANANA");
        job.setProgress(0);
        job.setReservedCredits(0);
        job.setDeadlineAt(LocalDateTime.now().plusMinutes(5));
        return job;
    }
}
