package com.cafe.inventoryservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives the Transactional Outbox's async relay: claims a batch of PENDING messages, then
 * publishes each one, isolating one message's failure from the rest of the batch - same
 * per-item try/catch shape as this service's own InboxPoller (the receive-side counterpart).
 * Not itself @Transactional: claimBatch() and publishOne()/recordFailure() are separate
 * transactions on OutboxMessagePublisher, called through that bean's proxy so each gets its own
 * transaction boundary (self-invocation within one class would silently bypass @Transactional).
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxMessagePublisher publisher;

    public OutboxPoller(OutboxMessagePublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:500ms}")
    public void poll() {
        List<Long> claimed = publisher.claimBatch();
        for (Long id : claimed) {
            try {
                publisher.publishOne(id);
            } catch (Exception e) {
                log.error("Outbox poller: failed to publish message {}", id, e);
                publisher.recordFailure(id, e.getMessage());
            }
        }
    }
}
