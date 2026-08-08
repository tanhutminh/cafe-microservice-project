package com.cafe.inventoryservice.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Same tunable-operational-parameter shape as order-service's SagaReconciliationProperties.
 * inventory-service has no config-repo entry today, so these defaults are the only source -
 * they're not just a config-server-unreachable fallback here.
 */
@ConfigurationProperties(prefix = "app.inbox")
public record InboxProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts
) {
}
