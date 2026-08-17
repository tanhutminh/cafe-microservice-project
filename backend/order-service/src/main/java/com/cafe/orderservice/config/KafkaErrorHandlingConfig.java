package com.cafe.orderservice.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Dead Letter Queue pattern for {@code inventory.stock-reservation.reply} and {@code
 * inventory.stock-commit.reply}: technical failures only (deserialization errors, a structurally
 * invalid reply per OrderSaga.validate, bugs, DB outages). Mirrors inventory-service's
 * KafkaErrorHandlingConfig exactly. Spring Boot auto-wires this single CommonErrorHandler bean into
 * the auto-configured listener container factory (order-service defines no factory of its own), so
 * no further wiring is needed.
 */
@Configuration
public class KafkaErrorHandlingConfig {

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition()));

    var backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setMaxElapsedTime(10_000L);

    return new DefaultErrorHandler(recoverer, backOff);
  }
}
