package com.cafe.inventoryservice.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Dead Letter Queue pattern for the three inventory command topics ({@code
 * inventory.reserve-stock.command}, {@code inventory.commit-stock.command}, {@code
 * inventory.release-stock.command}): a record whose listener invocation throws is retried with the
 * exponential backoff configured below and then routed to {@code <topic>.dlq} instead of blocking
 * the consumer as a poison pill; an error Spring Kafka classifies as fatal, such as a
 * deserialization failure, skips the retries and is routed there on the first attempt. Only
 * technical failures get that far - a deserialization error, a structurally invalid command
 * rejected by the listener's Bean Validation check, a DB outage while the command is being
 * recorded. A business outcome like "insufficient stock" cannot: receiving a command only records
 * it in the Transactional Inbox, and the reserve/commit/release step that decides such an outcome
 * runs later on the inbox worker's own thread, outside any listener invocation this handler wraps.
 *
 * <p>Spring Boot auto-wires this single CommonErrorHandler bean into the auto-configured listener
 * container factory (inventory-service defines no factory of its own), so no further wiring is
 * needed.
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
