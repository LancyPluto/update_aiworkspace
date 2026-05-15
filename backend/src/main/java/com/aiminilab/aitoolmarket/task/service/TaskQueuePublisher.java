package com.aiminilab.aitoolmarket.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String queueName;
    private final AppProperties appProperties;

    public TaskQueuePublisher(
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

    public boolean publish(Long taskId) {
        try {
            String message = objectMapper.writeValueAsString(Map.of("taskId", taskId));
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
            LOGGER.warn("failed to publish task, taskId={}, backend={}, redisQueue={}, rabbitExchange={}",
                    taskId,
                    appProperties.getTaskQueueBackend(),
                    queueName,
                    appProperties.getRabbitmq().getTaskExchange(),
                    exception);
            return false;
        }
    }
}
