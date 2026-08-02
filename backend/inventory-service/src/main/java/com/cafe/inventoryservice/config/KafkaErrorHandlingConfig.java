package com.cafe.inventoryservice.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Dead Letter Queue pattern for {@code inventory.reserve-stock.command}: technical failures
 * only (deserialization errors, bugs, DB outages) - StockReservationService never throws for
 * a business "insufficient stock" outcome, it returns a normal failure reply instead, so that
 * path never reaches this handler. Spring Boot auto-wires this single CommonErrorHandler bean
 * into the auto-configured listener container factory (inventory-service defines no factory
 * of its own), so no further wiring is needed.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition()));

        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
