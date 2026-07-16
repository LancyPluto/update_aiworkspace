package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.task.service.QueueOperationsService;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "WORKFLOW_RABBIT_GATE_ENABLED", matches = "(?i)true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TaskQueueRabbitMqRealGateTest {

    private static final String GATE_PREFIX = "workflow_p0_gate_";
    private static final String RUN_SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String NAME_ROOT = GATE_PREFIX + RUN_SUFFIX;
    private static final String TASK_EXCHANGE = NAME_ROOT + "_task_exchange";
    private static final String TASK_QUEUE = NAME_ROOT + "_task_queue";
    private static final String TASK_ROUTING_KEY = NAME_ROOT + "_task_route";
    private static final String MISSING_ROUTING_KEY = NAME_ROOT + "_missing_route";
    private static final String DEAD_EXCHANGE = NAME_ROOT + "_dead_exchange";
    private static final String DEAD_QUEUE = NAME_ROOT + "_dead_queue";
    private static final String DEAD_ROUTING_KEY = NAME_ROOT + "_dead_route";
    private static final long CONFIRM_TIMEOUT_MS = 5_000;
    private static final long RECEIVE_TIMEOUT_MS = 5_000;

    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate rabbitTemplate;
    private RabbitAdmin rabbitAdmin;
    private AppProperties appProperties;
    private TaskQueuePublisher publisher;
    private boolean taskExchangeDeclared;
    private boolean deadExchangeDeclared;
    private boolean taskQueueDeclared;
    private boolean deadQueueDeclared;

    @BeforeAll
    void connectToIsolatedLocalBroker() {
        assertGateResourceNames();
        String host = localHostOnly(environmentOrDefault("WORKFLOW_RABBIT_GATE_HOST", "127.0.0.1"));
        int port = validPort(environmentOrDefault("WORKFLOW_RABBIT_GATE_PORT", "5672"));

        connectionFactory = new CachingConnectionFactory(host, port);
        connectionFactory.setUsername(environmentOrDefault("WORKFLOW_RABBIT_GATE_USERNAME", "guest"));
        connectionFactory.setPassword(environmentOrDefault("WORKFLOW_RABBIT_GATE_PASSWORD", "guest"));
        connectionFactory.setVirtualHost(environmentOrDefault("WORKFLOW_RABBIT_GATE_VHOST", "/"));
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        connectionFactory.getRabbitConnectionFactory().setConnectionTimeout((int) CONFIRM_TIMEOUT_MS);
        connectionFactory.getRabbitConnectionFactory().setHandshakeTimeout((int) CONFIRM_TIMEOUT_MS);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        rabbitAdmin = new RabbitAdmin(connectionFactory);
        appProperties = gateProperties(TASK_ROUTING_KEY);
        publisher = publisherFor(appProperties);
    }

    @BeforeEach
    void declareIsolatedTopology() {
        DirectExchange taskExchange = new DirectExchange(TASK_EXCHANGE, false, true);
        DirectExchange deadExchange = new DirectExchange(DEAD_EXCHANGE, false, true);
        Queue taskQueue = QueueBuilder.nonDurable(TASK_QUEUE)
                .autoDelete()
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
        Queue deadQueue = QueueBuilder.nonDurable(DEAD_QUEUE).autoDelete().build();

        rabbitAdmin.declareExchange(taskExchange);
        taskExchangeDeclared = true;
        rabbitAdmin.declareExchange(deadExchange);
        deadExchangeDeclared = true;
        rabbitAdmin.declareQueue(taskQueue);
        taskQueueDeclared = true;
        rabbitAdmin.declareQueue(deadQueue);
        deadQueueDeclared = true;
        rabbitAdmin.declareBinding(BindingBuilder.bind(taskQueue).to(taskExchange).with(TASK_ROUTING_KEY));
        rabbitAdmin.declareBinding(BindingBuilder.bind(deadQueue).to(deadExchange).with(DEAD_ROUTING_KEY));
    }

    @AfterEach
    void removeIsolatedTopology() {
        cleanupTopology();
    }

    @AfterAll
    void closeBrokerConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void routedPublishWaitsForBrokerAckAndMessageIsActuallyQueued() {
        String payload = "{\"taskId\":71001,\"gate\":\"publisher-confirm\"}";

        assertThat(publisher.publish(71_001L, payload)).isTrue();

        Message queued = rabbitTemplate.receive(TASK_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(queued).isNotNull();
        assertThat(new String(queued.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void mandatoryPublishToMissingRouteFailsAsReturnedUnroutableMessage() {
        TaskQueuePublisher unroutablePublisher = publisherFor(gateProperties(MISSING_ROUTING_KEY));

        assertThatThrownBy(() -> unroutablePublisher.publish(71_002L, "{\"taskId\":71002}"))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("returned unroutable")
                .hasMessageContaining("NO_ROUTE")
                .hasMessageContaining(MISSING_ROUTING_KEY);
        assertThat(messageCount(TASK_QUEUE)).isZero();
    }

    @Test
    void manualDlqReplayConfirmsRepublishBeforeAcknowledgingOriginal() throws Exception {
        String payload = "{\"taskId\":71003,\"gate\":\"dlq-replay\"}";
        assertThat(publisher.publish(71_003L, payload)).isTrue();
        rejectOneTaskMessageToDeadLetterQueue();
        awaitMessageCount(DEAD_QUEUE, 1);

        QueueOperationsService operations = new QueueOperationsService(
                rabbitAdmin,
                rabbitTemplate,
                appProperties,
                publisher
        );
        Map<String, Object> result = operations.requeueDead(1);

        assertThat(result).containsEntry("available", true).containsEntry("requeued", 1);
        Message replayed = rabbitTemplate.receive(TASK_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(replayed).isNotNull();
        assertThat(new String(replayed.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
        assertThat(replayed.getMessageProperties().getHeaders()).doesNotContainKey("x-death");
        awaitMessageCount(DEAD_QUEUE, 0);
    }

    private void rejectOneTaskMessageToDeadLetterQueue() {
        rabbitTemplate.execute(channel -> {
            GetResponse response = channel.basicGet(TASK_QUEUE, false);
            if (response == null) {
                throw new IllegalStateException("Expected one message in the RabbitMQ gate task queue");
            }
            channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
            return null;
        });
    }

    private TaskQueuePublisher publisherFor(AppProperties properties) {
        return new TaskQueuePublisher(
                mock(StringRedisTemplate.class),
                rabbitTemplate,
                new ObjectMapper(),
                NAME_ROOT + "_unused_redis_queue",
                properties,
                CONFIRM_TIMEOUT_MS
        );
    }

    private static AppProperties gateProperties(String routingKey) {
        AppProperties properties = new AppProperties();
        properties.setTaskQueueBackend("rabbitmq");
        properties.getRabbitmq().setTaskExchange(TASK_EXCHANGE);
        properties.getRabbitmq().setTaskRoutingKey(routingKey);
        properties.getRabbitmq().setTaskQueue(TASK_QUEUE);
        properties.getRabbitmq().setDeadQueue(DEAD_QUEUE);
        properties.getRabbitmq().setRetryQueuePrefix(NAME_ROOT + "_retry_queue");
        return properties;
    }

    private void awaitMessageCount(String queueName, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECEIVE_TIMEOUT_MS);
        long actual = messageCount(queueName);
        while (actual != expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            actual = messageCount(queueName);
        }
        assertThat(actual).as("message count for %s", queueName).isEqualTo(expected);
    }

    private long messageCount(String queueName) {
        Long count = rabbitTemplate.execute(channel -> channel.messageCount(queueName));
        if (count == null) {
            throw new IllegalStateException("RabbitMQ did not return a message count for " + queueName);
        }
        return count;
    }

    private void cleanupTopology() {
        if (rabbitAdmin == null) {
            return;
        }
        assertGateResourceNames();
        if (taskExchangeDeclared) {
            rabbitAdmin.deleteExchange(TASK_EXCHANGE);
            taskExchangeDeclared = false;
        }
        if (deadExchangeDeclared) {
            rabbitAdmin.deleteExchange(DEAD_EXCHANGE);
            deadExchangeDeclared = false;
        }
        if (taskQueueDeclared) {
            rabbitAdmin.deleteQueue(TASK_QUEUE);
            taskQueueDeclared = false;
        }
        if (deadQueueDeclared) {
            rabbitAdmin.deleteQueue(DEAD_QUEUE);
            deadQueueDeclared = false;
        }
    }

    private static void assertGateResourceNames() {
        assertGateName(TASK_EXCHANGE);
        assertGateName(TASK_QUEUE);
        assertGateName(DEAD_EXCHANGE);
        assertGateName(DEAD_QUEUE);
    }

    private static void assertGateName(String name) {
        if (!name.startsWith(GATE_PREFIX)) {
            throw new IllegalStateException("RabbitMQ gate resource must start with " + GATE_PREFIX);
        }
    }

    private static String localHostOnly(String host) {
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
            throw new IllegalStateException(
                    "WORKFLOW_RABBIT_GATE_HOST must be 127.0.0.1 or localhost; refusing remote broker: " + host
            );
        }
        return host;
    }

    private static int validPort(String rawPort) {
        try {
            int port = Integer.parseInt(rawPort);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("out of range");
            }
            return port;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("WORKFLOW_RABBIT_GATE_PORT must be between 1 and 65535", exception);
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
