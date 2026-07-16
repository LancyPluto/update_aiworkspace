package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.task.entity.TaskOutboxEvent;
import com.aiminilab.aitoolmarket.task.mapper.TaskOutboxMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.aiminilab.aitoolmarket.task.service.impl.TaskOutboxServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOutboxPublisherConfirmationTest {

    @Test
    void publisherConfirmFailureKeepsOutboxRetryableAndNeverMarksItSent() {
        TaskOutboxMapper mapper = mock(TaskOutboxMapper.class);
        TaskQueuePublisher publisher = mock(TaskQueuePublisher.class);
        TaskMetrics metrics = mock(TaskMetrics.class);
        TaskOutboxServiceImpl service = new TaskOutboxServiceImpl(
                mapper,
                publisher,
                new ObjectMapper(),
                metrics
        );
        TaskOutboxEvent event = pendingEvent();
        when(mapper.findPending(10)).thenReturn(List.of(event));
        doThrow(new AmqpException("RabbitMQ publisher NACK"))
                .when(publisher).publish(event.getTaskId(), event.getPayloadJson());

        assertThat(service.dispatchPending(10)).isZero();

        verify(mapper, never()).markSent(event.getId());
        verify(mapper).markFailed(
                eq(event.getId()),
                any(LocalDateTime.class),
                contains("publisher NACK")
        );
        verify(metrics).recordQueuePublishFailure(event.getEventType());
    }

    private static TaskOutboxEvent pendingEvent() {
        TaskOutboxEvent event = new TaskOutboxEvent();
        event.setId(9L);
        event.setTaskId(42L);
        event.setEventType("TASK_CREATED");
        event.setPayloadJson("{\"taskId\":42}");
        event.setStatus("PENDING");
        event.setRetryCount(0);
        return event;
    }
}
