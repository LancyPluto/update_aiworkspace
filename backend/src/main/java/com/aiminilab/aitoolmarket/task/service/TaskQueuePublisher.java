package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class TaskQueuePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskQueuePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String queueName;
    private final AppProperties appProperties;
    private final long publisherConfirmTimeoutMs;

    public TaskQueuePublisher(
            StringRedisTemplate redisTemplate,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${app.ai-task-queue:ai:task:queue}") String queueName,
            AppProperties appProperties,
            @Value("${app.rabbitmq.publisher-confirm-timeout-ms:5000}") long publisherConfirmTimeoutMs
    ) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
        this.appProperties = appProperties;
        this.publisherConfirmTimeoutMs = Math.max(1, publisherConfirmTimeoutMs);
    }

    public boolean publish(Long taskId, String payloadJson) {
        try {
            String message = payloadJson;
            if (message == null || message.isBlank()) {
                message = objectMapper.writeValueAsString(Map.of("taskId", taskId));
            }
            if ("rabbitmq".equalsIgnoreCase(appProperties.getTaskQueueBackend())) {
                CorrelationData correlationData = correlationData("task-" + taskId);
                rabbitTemplate.convertAndSend(
                        appProperties.getRabbitmq().getTaskExchange(),
                        appProperties.getRabbitmq().getTaskRoutingKey(),
                        message,
                        correlationData
                );
                awaitBrokerAck(correlationData);
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
            if (exception instanceof AmqpException amqpException) {
                throw amqpException;
            }
            throw new AmqpException("Failed to publish task " + taskId, exception);
        }
    }

    public void publishConfirmed(Message message) {
        CorrelationData correlationData = correlationData("dlq-replay");
        rabbitTemplate.send(
                appProperties.getRabbitmq().getTaskExchange(),
                appProperties.getRabbitmq().getTaskRoutingKey(),
                message,
                correlationData
        );
        awaitBrokerAck(correlationData);
    }

    private CorrelationData correlationData(String prefix) {
        return new CorrelationData(prefix + "-" + UUID.randomUUID());
    }

    private void awaitBrokerAck(CorrelationData correlationData) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture().get(publisherConfirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new AmqpException(
                    "RabbitMQ publisher confirm timed out after " + publisherConfirmTimeoutMs + "ms",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for RabbitMQ publisher confirm", exception);
        } catch (ExecutionException exception) {
            throw new AmqpException("RabbitMQ publisher confirm failed", exception.getCause());
        }

        ReturnedMessage returned = correlationData.getReturned();
        if (returned != null) {
            throw new AmqpException(
                    "RabbitMQ returned unroutable message: replyCode=" + returned.getReplyCode()
                            + ", replyText=" + returned.getReplyText()
                            + ", exchange=" + returned.getExchange()
                            + ", routingKey=" + returned.getRoutingKey()
            );
        }
        if (!confirm.isAck()) {
            String reason = confirm.getReason() == null || confirm.getReason().isBlank()
                    ? "no reason supplied"
                    : confirm.getReason();
            throw new AmqpException("RabbitMQ publisher NACK: " + reason);
        }
    }
}
