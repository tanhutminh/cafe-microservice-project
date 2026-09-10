package com.cafe.inventoryservice.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable operational parameters for the Transactional Outbox, configured locally in this service's
 * own application.yml under app.outbox - same tunable-operational-parameter category as
 * InboxProperties. The @DefaultValue fallbacks are a safety net for any key omitted from
 * application.yml, so the poller still runs with sane defaults rather than binding batchSize to 0.
 * publishTimeout is the one field beyond the InboxProperties mirror: it bounds how long
 * OutboxMessagePublisher blocks on KafkaTemplate's send future before treating the attempt as
 * failed, since an outbox relay must itself decide when to give up waiting for a broker ack.
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
    @DefaultValue("500ms") Duration pollInterval,
    @DefaultValue("20") int batchSize,
    @DefaultValue("5") int maxAttempts,
    @DefaultValue("5s") Duration publishTimeout) {}
