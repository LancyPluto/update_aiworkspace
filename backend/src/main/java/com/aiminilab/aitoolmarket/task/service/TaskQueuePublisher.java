package com.aiminilab.aitoolmarket.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TaskQueuePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskQueuePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String queueName;

    public TaskQueuePublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.ai-task-queue:ai:task:queue}") String queueName
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    public boolean publish(Long taskId, String payloadJson) {
        try {
            String message = payloadJson;
            if (message == null || message.isBlank()) {
                message = objectMapper.writeValueAsString(Map.of("taskId", taskId));
            }
            redisTemplate.opsForList().rightPush(queueName, message);
            return true;
        } catch (Exception exception) {
            LOGGER.warn("failed to publish task to redis queue, taskId={}, queue={}", taskId, queueName, exception);
            return false;
        }
    }
}
