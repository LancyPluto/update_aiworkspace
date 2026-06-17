package com.aiminilab.aitoolmarket.subject.service;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SubjectQueuePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubjectQueuePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String queueName;
    private final AppProperties appProperties;

    public SubjectQueuePublisher(
            StringRedisTemplate redisTemplate,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${app.ai-task-queue:ai:task:queue}") String queueName,
            AppProperties appProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
        this.appProperties = appProperties;
    }

    public boolean publish(String subjectCode, Long userId) {
        return publishMessage("subject_sync", subjectCode, userId, null);
    }

    public boolean publishDelete(String subjectCode, Long userId, String upstreamElementId) {
        return publishMessage("subject_delete", subjectCode, userId, upstreamElementId);
    }

    private boolean publishMessage(String messageType, String subjectCode, Long userId, String upstreamElementId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", messageType);
            payload.put("subjectCode", subjectCode);
            payload.put("userId", userId);
            if (upstreamElementId != null && !upstreamElementId.isBlank()) {
                payload.put("upstreamElementId", upstreamElementId);
            }
            String message = objectMapper.writeValueAsString(payload);
            if ("rabbitmq".equalsIgnoreCase(appProperties.getTaskQueueBackend())) {
                rabbitTemplate.convertAndSend(
                        appProperties.getRabbitmq().getTaskExchange(),
                        appProperties.getRabbitmq().getTaskRoutingKey(),
                        message
                );
            } else {
                redisTemplate.opsForList().rightPush(queueName, message);
            }
            return true;
        } catch (Exception exception) {
            LOGGER.warn(
                    "failed to publish subject message type={}, subjectCode={}, userId={}",
                    messageType,
                    subjectCode,
                    userId,
                    exception
            );
            return false;
        }
    }
}
