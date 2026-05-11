package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.task.entity.TaskOutboxEvent;
import com.aiminilab.aitoolmarket.task.mapper.TaskOutboxMapper;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final TaskOutboxMapper taskOutboxMapper;
    private final TaskQueuePublisher taskQueuePublisher;
    private final ObjectMapper objectMapper;

    public TaskOutboxServiceImpl(TaskOutboxMapper taskOutboxMapper,
                                 TaskQueuePublisher taskQueuePublisher,
                                 ObjectMapper objectMapper) {
        this.taskOutboxMapper = taskOutboxMapper;
        this.taskQueuePublisher = taskQueuePublisher;
        this.objectMapper = objectMapper;
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
            if (taskQueuePublisher.publish(event.getTaskId())) {
                return true;
            }
            taskOutboxMapper.markFailed(event.getId(), LocalDateTime.now().plusMinutes(1), "publish returned false");
            return false;
        } catch (RuntimeException exception) {
            taskOutboxMapper.markFailed(event.getId(), LocalDateTime.now().plusMinutes(1), exception.getMessage());
            return false;
        }
    }

    private String payload(Long taskId) {
        try {
            return objectMapper.writeValueAsString(Map.of("taskId", taskId));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize task outbox payload", exception);
        }
    }
}
