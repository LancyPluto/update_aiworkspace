package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.config.AppProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@Service
public class QueueOperationsService {

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;

    public QueueOperationsService(AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate, AppProperties appProperties) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.appProperties = appProperties;
    }

    public Map<String, Object> stats() {
        String taskQueue = appProperties.getRabbitmq().getTaskQueue();
        String deadQueue = appProperties.getRabbitmq().getDeadQueue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backend", appProperties.getTaskQueueBackend());
        result.put("exchange", appProperties.getRabbitmq().getTaskExchange());
        result.put("routingKey", appProperties.getRabbitmq().getTaskRoutingKey());
        result.put("taskQueue", queueStats(taskQueue));
        result.put("deadQueue", queueStats(deadQueue));
        return result;
    }

    public Map<String, Object> requeueDead(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int moved = 0;
        String deadQueue = appProperties.getRabbitmq().getDeadQueue();
        try {
            for (int index = 0; index < boundedLimit; index++) {
                Message message = rabbitTemplate.receive(deadQueue, 500);
                if (message == null) {
                    break;
                }
                resetRetryHeaders(message);
                rabbitTemplate.send(
                        appProperties.getRabbitmq().getTaskExchange(),
                        appProperties.getRabbitmq().getTaskRoutingKey(),
                        message
                );
                moved++;
            }
        } catch (AmqpException exception) {
            return Map.of("requested", boundedLimit, "requeued", moved, "deadQueue", deadQueue,
                    "available", false, "error", exception.getMessage());
        }
        return Map.of("requested", boundedLimit, "requeued", moved, "deadQueue", deadQueue, "available", true);
    }

    private Map<String, Object> queueStats(String queueName) {
        Properties properties;
        try {
            properties = amqpAdmin.getQueueProperties(queueName);
        } catch (AmqpException exception) {
            return Map.of("name", queueName, "available", false, "messageCount", 0, "consumerCount", 0,
                    "error", exception.getMessage());
        }
        if (properties == null) {
            return Map.of("name", queueName, "available", false, "messageCount", 0, "consumerCount", 0);
        }
        return Map.of(
                "name", queueName,
                "available", true,
                "messageCount", number(properties.get("QUEUE_MESSAGE_COUNT")),
                "consumerCount", number(properties.get("QUEUE_CONSUMER_COUNT"))
        );
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static void resetRetryHeaders(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        headers.remove("x-retry-count");
        headers.remove("x-last-error");
        headers.remove("x-dead-reason");
        headers.remove("x-death");
        headers.remove("x-first-death-exchange");
        headers.remove("x-first-death-queue");
        headers.remove("x-first-death-reason");
    }
}
