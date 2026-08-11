package com.cafe.inventoryservice.outbox;

/**
 * Lifecycle of an {@link OutboxMessage}: PENDING (written, not yet claimed) -&gt; PROCESSING
 * (claimed by a poller run, Kafka send in flight) -&gt; PUBLISHED (broker ack received) or FAILED
 * (permanently gave up after app.outbox.max-attempts). A failed attempt under the retry budget
 * goes back to PENDING rather than a separate RETRY state, mirroring InboxStatus.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
