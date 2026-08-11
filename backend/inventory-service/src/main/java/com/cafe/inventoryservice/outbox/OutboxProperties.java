package com.cafe.inventoryservice.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Values are served by config-server (config-repo/inventory-service.yml), not this service's
 * own application.yml - same tunable-operational-parameter category as InboxProperties. The
 * @DefaultValue fallbacks only matter because inventory-service's config import is
 * "optional:configserver:" - if config-server is unreachable, the poller should still run with
 * sane defaults rather than bind batchSize to 0. publishTimeout is the one field beyond the
 * InboxProperties mirror: it bounds how long OutboxMessagePublisher blocks on KafkaTemplate's
 * send future before treating the attempt as failed, since an outbox relay must itself decide
 * when to give up waiting for a broker ack.
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5s") Duration publishTimeout
) {
}
