package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.task.service.QueueOperationsService;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueOperationsServiceTest {

    private static final long DELIVERY_TAG = 73L;

    private RabbitTemplate rabbitTemplate;
    private TaskQueuePublisher taskQueuePublisher;
    private Channel channel;
    private QueueOperationsService service;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        taskQueuePublisher = mock(TaskQueuePublisher.class);
        channel = mock(Channel.class);
        AppProperties appProperties = new AppProperties();
        service = new QueueOperationsService(
                mock(AmqpAdmin.class),
                rabbitTemplate,
                appProperties,
                taskQueuePublisher
        );
        doAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        }).when(rabbitTemplate).execute(any(ChannelCallback.class));
    }

    @Test
    void dlqReplayAcknowledgesOriginalOnlyAfterConfirmedRepublish() throws Exception {
        when(channel.basicGet("ai.tool.normal.dead", false)).thenReturn(deadMessage());

        Map<String, Object> result = service.requeueDead(1);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        InOrder publishThenAck = inOrder(taskQueuePublisher, channel);
        publishThenAck.verify(taskQueuePublisher).publishConfirmed(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
                .doesNotContainKeys("x-retry-count", "x-last-error", "x-death");
        publishThenAck.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(DELIVERY_TAG, false, true);
        assertThat(result).containsEntry("requeued", 1).containsEntry("available", true);
    }

    @Test
    void dlqReplayNacksAndRequeuesOriginalWhenRepublishIsNotConfirmed() throws Exception {
        when(channel.basicGet("ai.tool.normal.dead", false)).thenReturn(deadMessage());
        doThrow(new AmqpException("publisher confirm timed out"))
                .when(taskQueuePublisher).publishConfirmed(any(Message.class));

        Map<String, Object> result = service.requeueDead(1);

        verify(channel, never()).basicAck(DELIVERY_TAG, false);
        verify(channel).basicNack(DELIVERY_TAG, false, true);
        assertThat(result)
                .containsEntry("requeued", 0)
                .containsEntry("available", false)
                .containsEntry("error", "publisher confirm timed out");
    }

    @Test
    void dlqReplayDoesNothingWhenDeadQueueIsEmpty() throws Exception {
        when(channel.basicGet("ai.tool.normal.dead", false)).thenReturn(null);

        Map<String, Object> result = service.requeueDead(1);

        verify(taskQueuePublisher, never()).publishConfirmed(any(Message.class));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(result).containsEntry("requeued", 0).containsEntry("available", true);
    }

    private static GetResponse deadMessage() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-retry-count", 3);
        headers.put("x-last-error", "worker failed");
        headers.put("x-death", "dead-letter metadata");
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .contentType("application/json")
                .build();
        Envelope envelope = new Envelope(
                DELIVERY_TAG,
                false,
                "ai.task.exchange.dlx",
                "dead"
        );
        return new GetResponse(envelope, properties, "{\"taskId\":42}".getBytes(), 0);
    }
}
