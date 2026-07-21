package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.ProviderCheckpointRequest;
import com.aiminilab.aitoolmarket.task.dto.ProviderCheckpointResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.task.service.impl.InternalTaskServiceImpl;
import com.aiminilab.aitoolmarket.task.support.ProviderCheckpointLimits;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunLockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTaskServiceRunLockTest {

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private WorkflowRunLockService workflowRunLockService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private InternalTaskServiceImpl target;

    private RecordingTransactionManager transactionManager;
    private InternalTaskService service;

    @BeforeEach
    void setUp() {
        transactionManager = new RecordingTransactionManager();
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        service = (InternalTaskService) proxyFactory.getProxy();
    }

    @Test
    void providerCheckpointLocksWorkflowRunBeforeGuardedUpdateInsideTransaction() {
        AiTask before = workflowChildTask(31L, "claim-31");
        before.setProviderCheckpointVersion(0);
        AiTask saved = workflowChildTask(31L, "claim-31");
        saved.setProviderCheckpointJson("{\"providerTaskId\":\"provider-31\"}");
        saved.setProviderCheckpointVersion(1);
        when(taskMapper.findById(31L))
                .thenReturn(Optional.of(before), Optional.of(saved));
        when(taskMapper.updateProviderCheckpointGuarded(
                31L,
                "claim-31",
                0,
                "{\"providerTaskId\":\"provider-31\"}"
        )).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
        when(workflowRunLockService.requireByChildTaskId(31L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });

        ProviderCheckpointResponse response = service.saveProviderCheckpoint(
                31L,
                new ProviderCheckpointRequest(
                        objectMapper.createObjectNode().put("providerTaskId", "provider-31"),
                        0,
                        "claim-31"
                )
        );

        assertThat(response.version()).isEqualTo(1);
        assertThat(transactionManager.commits).isEqualTo(1);
        InOrder order = inOrder(taskMapper, workflowRunLockService);
        order.verify(taskMapper).findById(31L);
        order.verify(workflowRunLockService).requireByChildTaskId(31L);
        order.verify(taskMapper).updateProviderCheckpointGuarded(
                31L,
                "claim-31",
                0,
                "{\"providerTaskId\":\"provider-31\"}"
        );
    }

    @Test
    void providerCheckpointAcceptsCompletedChineseResultJustAboveLegacyLimit() {
        ObjectNode checkpoint = objectMapper.createObjectNode();
        checkpoint.put("kind", "COMIC_OPERATION");
        checkpoint.put("status", "COMPLETED");
        checkpoint.putObject("result")
                .putObject("script")
                .put("screenplay", "中".repeat(20_000));
        String checkpointJson = checkpoint.toString();
        assertThat(checkpointJson.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(60_000)
                .isLessThan(ProviderCheckpointLimits.MAX_PERSISTED_BYTES);

        AiTask before = workflowChildTask(33L, "claim-33");
        before.setProviderCheckpointVersion(0);
        AiTask saved = workflowChildTask(33L, "claim-33");
        saved.setProviderCheckpointJson(checkpointJson);
        saved.setProviderCheckpointVersion(1);
        when(taskMapper.findById(33L)).thenReturn(Optional.of(before), Optional.of(saved));
        when(taskMapper.updateProviderCheckpointGuarded(33L, "claim-33", 0, checkpointJson))
                .thenReturn(1);

        ProviderCheckpointResponse response = service.saveProviderCheckpoint(
                33L,
                new ProviderCheckpointRequest(checkpoint, 0, "claim-33")
        );

        assertThat(response.checkpoint()).isEqualTo(checkpoint);
        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    void providerCheckpointRejectsResultAboveHardLimitBeforeDatabaseWrite() {
        ObjectNode checkpoint = objectMapper.createObjectNode();
        checkpoint.put("kind", "COMIC_OPERATION");
        checkpoint.put("status", "COMPLETED");
        checkpoint.putObject("result")
                .putObject("script")
                .put("screenplay", "中".repeat(ProviderCheckpointLimits.MAX_PERSISTED_BYTES / 3 + 1));
        assertThat(checkpoint.toString().getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(ProviderCheckpointLimits.MAX_PERSISTED_BYTES);

        AiTask before = workflowChildTask(34L, "claim-34");
        before.setProviderCheckpointVersion(0);
        when(taskMapper.findById(34L)).thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.saveProviderCheckpoint(
                34L,
                new ProviderCheckpointRequest(checkpoint, 0, "claim-34")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("检查点过大");

        verify(taskMapper, never()).updateProviderCheckpointGuarded(any(), any(), anyInt(), any());
        verify(workflowRunLockService, never()).requireByChildTaskId(any());
    }

    @Test
    void markProcessingLocksWorkflowRunBeforeGuardedUpdateInsideTransaction() {
        AiTask task = workflowChildTask(32L, "claim-32");
        when(taskMapper.findById(32L)).thenReturn(Optional.of(task));
        when(taskMapper.markProcessingGuarded(
                eq(32L),
                eq("claim-32"),
                eq(35),
                eq("rendering"),
                anyList()
        )).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
        when(workflowRunLockService.requireByChildTaskId(32L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });

        service.markProcessing(32L, new WorkerProcessingRequest(35, "rendering", "claim-32"));

        assertThat(transactionManager.commits).isEqualTo(1);
        InOrder order = inOrder(taskMapper, workflowRunLockService);
        order.verify(taskMapper).findById(32L);
        order.verify(workflowRunLockService).requireByChildTaskId(32L);
        order.verify(taskMapper).markProcessingGuarded(
                32L,
                "claim-32",
                35,
                "rendering",
                List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name())
        );
    }

    private AiTask workflowChildTask(Long taskId, String claimToken) {
        AiTask task = new AiTask();
        task.setId(taskId);
        task.setTaskNo("TASK-" + taskId);
        task.setStatus(TaskStatus.PROCESSING.name());
        task.setClaimToken(claimToken);
        task.setParamsJson("{\"workflowStep\":true}");
        return task;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
