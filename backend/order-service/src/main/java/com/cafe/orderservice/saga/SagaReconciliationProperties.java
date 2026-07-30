package com.cafe.orderservice.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Values are served by config-server (config-repo/order-service.yml), not this service's own
 * application.yml - same tunable-operational-parameter category as auth-service's JWT TTLs.
 * The @DefaultValue fallbacks only matter because order-service's config import is
 * "optional:configserver:" (unlike auth-service/gateway's fail-fast import) - if config-server
 * is unreachable, the reconciliation job should still run with sane defaults rather than bind
 * stuckThreshold to null or maxRetries to 0.
 */
@ConfigurationProperties(prefix = "app.saga-reconciliation")
public record SagaReconciliationProperties(
        @DefaultValue("60s") Duration stuckThreshold,
        @DefaultValue("3") int maxRetries
) {
}
