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
 * inventory.stock-commit.reply}: a record whose listener invocation throws is retried with the
 * exponential backoff configured below and then routed to {@code <topic>.dlq} instead of blocking
 * the consumer as a poison pill; an error Spring Kafka classifies as fatal, such as a
 * deserialization failure, skips the retries and is routed there on the first attempt. Only
 * technical failures get that far - a deserialization error, a structurally invalid reply rejected
 * by the listener's Bean Validation check, a DB outage while the reply is being applied. A business
 * outcome like "insufficient stock" cannot: inventory-service sends it as an ordinary failure
 * reply, which the listener answers by compensating the order rather than by throwing.
 *
 * <p>Spring Boot auto-wires this single CommonErrorHandler bean into the auto-configured listener
 * container factory (order-service defines no factory of its own), so no further wiring is needed.
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
