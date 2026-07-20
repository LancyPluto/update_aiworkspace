package com.aiminilab.aitoolmarket.community.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.dto.PublishPostRequest;
import com.aiminilab.aitoolmarket.community.entity.CommunityPost;
import com.aiminilab.aitoolmarket.community.mapper.CommunityCollectionMapper;
import com.aiminilab.aitoolmarket.community.mapper.CommunityEventMapper;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostMapper;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostReportMapper;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long TASK_ID = 11L;
    private static final Long POST_ID = 13L;

    private CommunityPostMapper postMapper;
    private TaskMapper taskMapper;
    private AssetStorageService assetStorageService;
    private CommunityServiceImpl service;

    @BeforeEach
    void setUp() {
        postMapper = mock(CommunityPostMapper.class);
        taskMapper = mock(TaskMapper.class);
        assetStorageService = mock(AssetStorageService.class);
        service = new CommunityServiceImpl(
                postMapper,
                mock(CommunityCollectionMapper.class),
                mock(CommunityEventMapper.class),
                mock(CommunityPostReportMapper.class),
                taskMapper,
                mock(UserMapper.class),
                new ObjectMapper(),
                assetStorageService
        );
    }

    @Test
    void publishFailsBeforeStatusUpdateWhenAssetCopyFails() {
        AiTask task = successfulTask();
        CommunityPost post = ownedPost();
        when(taskMapper.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(postMapper.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(post));
        when(assetStorageService.maybeMoveUrl(anyString(), eq(true)))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "copy failed"));

        assertThatThrownBy(() -> service.publish(
                USER_ID,
                new PublishPostRequest(TASK_ID, null, null, null, null, null)
        )).isInstanceOf(BusinessException.class);

        verify(postMapper, never()).updateOwnerStatus(anyLong(), anyLong(), anyString());
        verify(postMapper, never()).updateMedia(anyLong(), any(), any());
    }

    @Test
    void unpublishFailsBeforeStatusUpdateWhenAssetCopyFails() {
        CommunityPost post = ownedPost();
        when(postMapper.findPostByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(assetStorageService.maybeMoveUrl(anyString(), eq(false)))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "copy failed"));

        assertThatThrownBy(() -> service.unpublish(USER_ID, POST_ID))
                .isInstanceOf(BusinessException.class);

        verify(postMapper, never()).updateOwnerStatus(anyLong(), anyLong(), anyString());
        verify(postMapper, never()).updateMedia(anyLong(), any(), any());
    }

    @Test
    void serializesOssMigrationsUntilTransactionCompletion() throws Exception {
        long secondPostId = POST_ID + 1;
        when(assetStorageService.isOssMode()).thenReturn(true);
        when(postMapper.findPostByIdForUpdate(POST_ID)).thenReturn(Optional.of(ownedPost(POST_ID)));
        when(postMapper.findPostByIdForUpdate(secondPostId)).thenReturn(Optional.of(ownedPost(secondPostId)));
        when(taskMapper.findResultResources(TASK_ID)).thenReturn(List.of());
        when(postMapper.updateOwnerStatus(anyLong(), eq(USER_ID), eq("UNPUBLISHED"))).thenReturn(1);

        CountDownLatch firstHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> runUnpublishTransaction(
                    POST_ID, null, firstHoldingLock, releaseFirst, null, failure));
            assertThat(firstHoldingLock.await(2, TimeUnit.SECONDS)).isTrue();

            executor.submit(() -> runUnpublishTransaction(
                    secondPostId, secondStarted, null, null, secondFinished, failure));
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            assertThat(secondFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private void runUnpublishTransaction(long postId,
                                         CountDownLatch started,
                                         CountDownLatch lockHeld,
                                         CountDownLatch release,
                                         CountDownLatch finished,
                                         AtomicReference<Throwable> failure) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            if (started != null) {
                started.countDown();
            }
            service.unpublish(USER_ID, postId);
            if (lockHeld != null) {
                lockHeld.countDown();
            }
            if (release != null) {
                release.await(2, TimeUnit.SECONDS);
            }
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            assertThat(synchronizations.get(0).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
            for (TransactionSynchronization synchronization : synchronizations) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
        } catch (Throwable exception) {
            failure.compareAndSet(null, exception);
            if (lockHeld != null) {
                lockHeld.countDown();
            }
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            if (finished != null) {
                finished.countDown();
            }
        }
    }

    private AiTask successfulTask() {
        AiTask task = new AiTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setStatus(TaskStatus.SUCCESS.name());
        return task;
    }

    private CommunityPost ownedPost() {
        return ownedPost(POST_ID);
    }

    private CommunityPost ownedPost(Long postId) {
        CommunityPost post = new CommunityPost();
        post.setId(postId);
        post.setTaskId(TASK_ID);
        post.setUserId(USER_ID);
        post.setCoverUrl("https://private.example.com/tasks/11/cover.png");
        post.setStatus("UNPUBLISHED");
        return post;
    }
}
