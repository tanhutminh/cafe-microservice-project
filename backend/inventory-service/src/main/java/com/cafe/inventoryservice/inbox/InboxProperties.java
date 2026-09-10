package com.cafe.inventoryservice.inbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable operational parameters for the Transactional Inbox, configured locally in this service's
 * own application.yml under app.inbox - same tunable-operational-parameter category as
 * order-service's SagaReconciliationProperties. The @DefaultValue fallbacks are a safety net for
 * any key omitted from application.yml, so the poller still runs with sane defaults rather than
 * binding batchSize to 0.
 */
@ConfigurationProperties(prefix = "app.inbox")
public record InboxProperties(
    @DefaultValue("500ms") Duration pollInterval,
    @DefaultValue("20") int batchSize,
    @DefaultValue("5") int maxAttempts) {}
