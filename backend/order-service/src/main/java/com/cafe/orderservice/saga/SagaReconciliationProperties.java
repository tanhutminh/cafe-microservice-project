package com.cafe.orderservice.saga;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable operational parameters for the saga reconciliation sweep, configured locally in this
 * service's own application.yml under app.saga-reconciliation - same tunable-operational-parameter
 * category as auth-service's JWT TTLs. The @DefaultValue fallbacks are a safety net for any key
 * omitted from application.yml, so the reconciliation job still runs with sane defaults rather than
 * binding stuckThreshold to null or maxRetries to 0.
 */
@ConfigurationProperties(prefix = "app.saga-reconciliation")
public record SagaReconciliationProperties(
    @DefaultValue("60s") Duration stuckThreshold, @DefaultValue("3") int maxRetries) {}
