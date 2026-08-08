package com.cafe.inventoryservice.inbox;

/**
 * Lifecycle of an {@link InboxMessage}: PENDING (received, not yet claimed) -&gt; PROCESSING
 * (claimed by a poller run, business logic in flight) -&gt; PROCESSED (done) or FAILED
 * (permanently gave up after app.inbox.max-attempts). A failed attempt under the retry budget
 * goes back to PENDING rather than a separate RETRY state - it's indistinguishable from a
 * fresh message to the poller.
 */
public enum InboxStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
