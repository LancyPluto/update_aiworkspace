package com.aiminilab.aitoolmarket.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RabbitMqConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        rabbitAdmin.setAutoStartup(true);
        return rabbitAdmin;
    }

    @Bean
    public ApplicationRunner rabbitTopologyInitializer(RabbitAdmin rabbitAdmin) {
        return args -> {
            try {
                rabbitAdmin.initialize();
            } catch (Exception exception) {
                log.warn("RabbitMQ topology initialization skipped: {}", exception.getMessage());
            }
        };
    }

    @Bean
    public DirectExchange aiTaskExchange(AppProperties appProperties) {
        return new DirectExchange(appProperties.getRabbitmq().getTaskExchange(), true, false);
    }

    @Bean
    public DirectExchange aiTaskDeadLetterExchange(AppProperties appProperties) {
        return new DirectExchange(appProperties.getRabbitmq().getTaskExchange() + ".dlx", true, false);
    }

    @Bean
    public Queue aiTaskQueue(AppProperties appProperties) {
        return QueueBuilder
                .durable(appProperties.getRabbitmq().getTaskQueue())
                .deadLetterExchange(appProperties.getRabbitmq().getTaskExchange() + ".dlx")
                .deadLetterRoutingKey("dead")
                .build();
    }

    @Bean
    public Queue aiTaskDeadLetterQueue(AppProperties appProperties) {
        return QueueBuilder.durable(appProperties.getRabbitmq().getDeadQueue()).build();
    }

    @Bean
    public Declarables aiTaskRetryQueues(AppProperties appProperties) {
        List<Declarable> retryQueues = new ArrayList<>();
        List<Integer> retryDelaysMs = appProperties.getRabbitmq().getRetryDelaysMs();
        for (int index = 0; index < retryDelaysMs.size(); index++) {
            retryQueues.add(QueueBuilder
                    .durable(appProperties.getRabbitmq().getRetryQueuePrefix() + "." + (index + 1))
                    .ttl(retryDelaysMs.get(index))
                    .deadLetterExchange(appProperties.getRabbitmq().getTaskExchange())
                    .deadLetterRoutingKey(appProperties.getRabbitmq().getTaskRoutingKey())
                    .build());
        }
        return new Declarables(retryQueues);
    }

    @Bean
    public Binding aiTaskBinding(Queue aiTaskQueue, DirectExchange aiTaskExchange, AppProperties appProperties) {
        return BindingBuilder
                .bind(aiTaskQueue)
                .to(aiTaskExchange)
                .with(appProperties.getRabbitmq().getTaskRoutingKey());
    }

    @Bean
    public Binding aiTaskDeadLetterBinding(Queue aiTaskDeadLetterQueue, DirectExchange aiTaskDeadLetterExchange) {
        return BindingBuilder.bind(aiTaskDeadLetterQueue).to(aiTaskDeadLetterExchange).with("dead");
    }
}
