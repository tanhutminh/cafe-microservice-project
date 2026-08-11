package com.cafe.orderservice.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Values are served by config-server (config-repo/order-service.yml), not this service's own
 * application.yml - same tunable-operational-parameter category as SagaReconciliationProperties.
 * The @DefaultValue fallbacks only matter because order-service's config import is
 * "optional:configserver:" - if config-server is unreachable, the poller should still run with
 * sane defaults rather than bind batchSize to 0. publishTimeout is the one field beyond the
 * Transactional Inbox's InboxProperties mirror: it bounds how long OutboxMessagePublisher blocks
 * on KafkaTemplate's send future before treating the attempt as failed, since - unlike a Kafka
 * listener's inbound receipt - an outbox relay must itself decide when to give up waiting for a
 * broker ack.
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5s") Duration publishTimeout
) {
}
