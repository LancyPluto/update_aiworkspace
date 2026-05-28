package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.task.entity.TaskOutboxEvent;
import com.aiminilab.aitoolmarket.task.mapper.TaskOutboxMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class TaskOutboxServiceImpl implements TaskOutboxService {

    private static final String EVENT_TASK_CREATED = "TASK_CREATED";
    private static final String EVENT_TASK_RETRY = "TASK_RETRY";
    private static final String STATUS_PENDING = "PENDING";
    private static final int MAX_PUBLISH_RETRIES = 5;

    private final TaskOutboxMapper taskOutboxMapper;
    private final TaskQueuePublisher taskQueuePublisher;
    private final ObjectMapper objectMapper;
    private final TaskMetrics taskMetrics;

    public TaskOutboxServiceImpl(TaskOutboxMapper taskOutboxMapper,
                                 TaskQueuePublisher taskQueuePublisher,
                                 ObjectMapper objectMapper,
                                 TaskMetrics taskMetrics) {
        this.taskOutboxMapper = taskOutboxMapper;
        this.taskQueuePublisher = taskQueuePublisher;
        this.objectMapper = objectMapper;
        this.taskMetrics = taskMetrics;
    }

    @Override
    public void enqueueTaskCreated(Long taskId) {
        enqueue(taskId, EVENT_TASK_CREATED);
    }

    @Override
    public void enqueueTaskRetry(Long taskId) {
        enqueue(taskId, EVENT_TASK_RETRY);
    }

    @Override
    @Transactional
    public int dispatchPending(int limit) {
        int sent = 0;
        for (TaskOutboxEvent event : taskOutboxMapper.findPending(Math.max(1, limit))) {
            if (publish(event)) {
                sent += taskOutboxMapper.markSent(event.getId());
            }
        }
        return sent;
    }

    private void enqueue(Long taskId, String eventType) {
        TaskOutboxEvent event = new TaskOutboxEvent();
        event.setTaskId(taskId);
        event.setEventType(eventType);
        event.setPayloadJson(payload(taskId));
        event.setStatus(STATUS_PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        try {
            taskOutboxMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // Idempotent task creation can race; one outbox event is enough.
        }
    }

    private boolean publish(TaskOutboxEvent event) {
        try {
            if (taskQueuePublisher.publish(event.getTaskId(), event.getPayloadJson())) {
                return true;
            }
            taskMetrics.recordQueuePublishFailure(event.getEventType());
            markPublishFailure(event, "publish returned false");
            return false;
        } catch (RuntimeException exception) {
            taskMetrics.recordQueuePublishFailure(event.getEventType());
            markPublishFailure(event, exception.getMessage());
            return false;
        }
    }

    private void markPublishFailure(TaskOutboxEvent event, String lastError) {
        String normalizedError = lastError == null || lastError.isBlank() ? "publish failed" : lastError;
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        if (retryCount + 1 >= MAX_PUBLISH_RETRIES) {
            taskOutboxMapper.markDead(event.getId(), normalizedError);
            return;
        }
        long delayMinutes = Math.min(30, 1L << retryCount);
        taskOutboxMapper.markFailed(event.getId(), LocalDateTime.now().plusMinutes(delayMinutes), normalizedError);
    }

    private String payload(Long taskId) {
        try {
            String traceId = MDC.get("traceId");
            if (traceId == null || traceId.isBlank()) {
                return objectMapper.writeValueAsString(Map.of("taskId", taskId));
            }
            return objectMapper.writeValueAsString(Map.of("taskId", taskId, "traceId", traceId));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize task outbox payload", exception);
        }
    }
}
