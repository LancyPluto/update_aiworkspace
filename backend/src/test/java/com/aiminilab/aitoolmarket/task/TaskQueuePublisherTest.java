package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskQueuePublisherTest {

    private static final String EXCHANGE = "ai.task.exchange";
    private static final String ROUTING_KEY = "tool.normal";

    private StringRedisTemplate redisTemplate;
    private RabbitTemplate rabbitTemplate;
    private TaskQueuePublisher publisher;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        AppProperties appProperties = new AppProperties();
        appProperties.setTaskQueueBackend("rabbitmq");
        publisher = new TaskQueuePublisher(
                redisTemplate,
                rabbitTemplate,
                new ObjectMapper(),
                "ai:task:queue",
                appProperties,
                50
        );
    }

    @Test
    void rabbitPublishSucceedsOnlyAfterBrokerAck() {
        completeConvertedMessageWith(new CorrelationData.Confirm(true, null), null);

        assertThat(publisher.publish(42L, "{\"taskId\":42}")).isTrue();

        verify(rabbitTemplate).convertAndSend(
                eq(EXCHANGE),
                eq(ROUTING_KEY),
                anyString(),
                any(CorrelationData.class)
        );
    }

    @Test
    void rabbitPublishThrowsWhenBrokerNacksMessage() {
        completeConvertedMessageWith(new CorrelationData.Confirm(false, "exchange unavailable"), null);

        assertThatThrownBy(() -> publisher.publish(42L, "{\"taskId\":42}"))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("NACK")
                .hasMessageContaining("exchange unavailable");
    }

    @Test
    void rabbitPublishThrowsWhenMandatoryMessageIsReturned() {
        Message returnedMessage = new Message("body".getBytes(), new MessageProperties());
        ReturnedMessage returned = new ReturnedMessage(
                returnedMessage,
                312,
                "NO_ROUTE",
                EXCHANGE,
                ROUTING_KEY
        );
        completeConvertedMessageWith(new CorrelationData.Confirm(true, null), returned);

        assertThatThrownBy(() -> publisher.publish(42L, "{\"taskId\":42}"))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("returned")
                .hasMessageContaining("NO_ROUTE");
    }

    @Test
    void rabbitPublishThrowsWhenBrokerConfirmTimesOut() {
        publisher = new TaskQueuePublisher(
                redisTemplate,
                rabbitTemplate,
                new ObjectMapper(),
                "ai:task:queue",
                rabbitProperties(),
                1
        );

        assertThatThrownBy(() -> publisher.publish(42L, "{\"taskId\":42}"))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void dlqReplayMessageAlsoWaitsForBrokerAck() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(EXCHANGE),
                eq(ROUTING_KEY),
                any(Message.class),
                any(CorrelationData.class)
        );

        Message message = new Message("body".getBytes(), new MessageProperties());
        publisher.publishConfirmed(message);

        verify(rabbitTemplate).send(
                eq(EXCHANGE),
                eq(ROUTING_KEY),
                eq(message),
                any(CorrelationData.class)
        );
    }

    private void completeConvertedMessageWith(CorrelationData.Confirm confirm, ReturnedMessage returned) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(returned);
            correlationData.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(EXCHANGE),
                eq(ROUTING_KEY),
                anyString(),
                any(CorrelationData.class)
        );
    }

    private static AppProperties rabbitProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.setTaskQueueBackend("rabbitmq");
        return appProperties;
    }
}
