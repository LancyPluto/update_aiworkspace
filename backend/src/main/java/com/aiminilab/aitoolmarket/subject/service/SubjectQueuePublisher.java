package com.aiminilab.aitoolmarket.subject.service;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
        try {
            String message = objectMapper.writeValueAsString(Map.of(
                    "messageType", "subject_sync",
                    "subjectCode", subjectCode,
                    "userId", userId
            ));
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
            LOGGER.warn("failed to publish subject sync, subjectCode={}, userId={}", subjectCode, userId, exception);
            return false;
        }
    }
}
