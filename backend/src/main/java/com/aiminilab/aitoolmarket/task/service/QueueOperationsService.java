package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@Service
public class QueueOperationsService {

    private static final MessagePropertiesConverter MESSAGE_PROPERTIES_CONVERTER =
            new DefaultMessagePropertiesConverter();

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;
    private final TaskQueuePublisher taskQueuePublisher;

    public QueueOperationsService(AmqpAdmin amqpAdmin,
                                  RabbitTemplate rabbitTemplate,
                                  AppProperties appProperties,
                                  TaskQueuePublisher taskQueuePublisher) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.appProperties = appProperties;
        this.taskQueuePublisher = taskQueuePublisher;
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
                Boolean requeued = rabbitTemplate.execute(channel -> requeueOne(channel, deadQueue));
                if (!Boolean.TRUE.equals(requeued)) {
                    break;
                }
                moved++;
            }
        } catch (AmqpException exception) {
            return Map.of("requested", boundedLimit, "requeued", moved, "deadQueue", deadQueue,
                    "available", false, "error", exception.getMessage());
        }
        return Map.of("requested", boundedLimit, "requeued", moved, "deadQueue", deadQueue, "available", true);
    }

    private boolean requeueOne(Channel channel, String deadQueue) throws Exception {
        GetResponse response = channel.basicGet(deadQueue, false);
        if (response == null) {
            return false;
        }

        long deliveryTag = response.getEnvelope().getDeliveryTag();
        Message message = new Message(
                response.getBody(),
                MESSAGE_PROPERTIES_CONVERTER.toMessageProperties(
                        response.getProps(),
                        response.getEnvelope(),
                        StandardCharsets.UTF_8.name()
                )
        );
        resetRetryHeaders(message);
        try {
            taskQueuePublisher.publishConfirmed(message);
            channel.basicAck(deliveryTag, false);
            return true;
        } catch (Exception exception) {
            nackForRetry(channel, deliveryTag, exception);
            throw exception;
        }
    }

    private static void nackForRetry(Channel channel, long deliveryTag, Exception publishFailure) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException nackFailure) {
            publishFailure.addSuppressed(nackFailure);
        }
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
