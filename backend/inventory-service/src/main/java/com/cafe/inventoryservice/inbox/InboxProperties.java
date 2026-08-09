package com.cafe.inventoryservice.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Values are served by config-server (config-repo/inventory-service.yml), not this service's
 * own application.yml - same tunable-operational-parameter category as order-service's
 * SagaReconciliationProperties. The @DefaultValue fallbacks only matter because
 * inventory-service's config import is "optional:configserver:" - if config-server is
 * unreachable, the poller should still run with sane defaults rather than bind batchSize to 0.
 */
@ConfigurationProperties(prefix = "app.inbox")
public record InboxProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts
) {
}
