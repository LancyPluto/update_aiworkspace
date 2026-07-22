package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.task.dto.ProviderCallbackRegistrationRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.entity.ProviderCallbackRegistration;
import com.aiminilab.aitoolmarket.task.mapper.ProviderCallbackMapper;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.impl.ProviderCallbackServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderCallbackServiceImplTest {

    @Test
    void registrationCreatesOpaqueHttpsCallbackBoundToActiveClaim() {
        ProviderCallbackMapper callbackMapper = mock(ProviderCallbackMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        AiTask task = activeTask();
        when(taskMapper.findById(42L)).thenReturn(Optional.of(task));
        doAnswer(invocation -> {
            ProviderCallbackRegistration registration = invocation.getArgument(0);
            registration.setId(7L);
            return 1;
        }).when(callbackMapper).insertRegistration(any());

        ProviderCallbackServiceImpl service = new ProviderCallbackServiceImpl(
                callbackMapper,
                taskMapper,
                new ObjectMapper(),
                "https://wlcloudai.com/",
                24
        );

        var response = service.register(
                42L,
                new ProviderCallbackRegistrationRequest("suno", "claim-42")
        );

        assertThat(response.registrationId()).isEqualTo(7L);
        assertThat(response.providerCode()).isEqualTo("suno_music");
        assertThat(response.callbackUrl()).matches(
                "https://wlcloudai\\.com/api/v1/provider-callbacks/suno/music/[a-f0-9]{64}"
        );
        verify(callbackMapper).insertRegistration(any());
    }

    @Test
    void duplicateCompletionCallbackIsAcknowledgedIdempotently() throws Exception {
        ProviderCallbackMapper callbackMapper = mock(ProviderCallbackMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        ProviderCallbackRegistration registration = new ProviderCallbackRegistration();
        registration.setId(9L);
        registration.setTaskId(42L);
        registration.setProviderCode("suno_music");
        when(callbackMapper.findActiveRegistration(any())).thenReturn(registration);
        when(callbackMapper.bindProviderTask(9L, "provider-task-1")).thenReturn(1);
        doThrow(new DuplicateKeyException("duplicate")).when(callbackMapper).insertInbox(any());

        ProviderCallbackServiceImpl service = new ProviderCallbackServiceImpl(
                callbackMapper,
                taskMapper,
                new ObjectMapper(),
                "https://wlcloudai.com",
                24
        );
        var payload = new ObjectMapper().readTree("""
                {
                  "code": 200,
                  "msg": "success",
                  "data": {
                    "callbackType": "complete",
                    "task_id": "provider-task-1",
                    "data": [{"id":"audio-1","audio_url":"https://cdn.example/song.mp3"}]
                  }
                }
                """);

        service.receiveSuno("a".repeat(64), payload);

        verify(callbackMapper).markRegistrationCompleted(9L);
    }

    private static AiTask activeTask() {
        AiTask task = new AiTask();
        task.setId(42L);
        task.setStatus("PROCESSING");
        task.setClaimToken("claim-42");
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(10));
        task.setCurrentRouteAttemptId(3L);
        return task;
    }
}
