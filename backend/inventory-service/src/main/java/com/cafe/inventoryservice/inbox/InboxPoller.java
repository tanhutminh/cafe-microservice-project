package com.cafe.inventoryservice.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives the Transactional Inbox's async worker: claims a batch of PENDING messages, then
 * processes each one, isolating one message's failure from the rest of the batch - same
 * per-item try/catch shape as OrderCheckoutSaga's OrderSagaReconciliationJob sweep. Not
 * itself @Transactional: claimBatch() and processOne()/recordFailure() are separate
 * transactions on InboxMessageProcessor, called through that bean's proxy so each gets its own
 * transaction boundary (self-invocation within one class would silently bypass @Transactional).
 */
@Component
public class InboxPoller {

    private static final Logger log = LoggerFactory.getLogger(InboxPoller.class);

    private final InboxMessageProcessor processor;

    public InboxPoller(InboxMessageProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.inbox.poll-interval:500ms}")
    public void poll() {
        List<String> claimed = processor.claimBatch();
        for (String correlationId : claimed) {
            try {
                processor.processOne(correlationId);
            } catch (Exception e) {
                log.error("Inbox poller: failed to process message {}", correlationId, e);
                processor.recordFailure(correlationId, e.getMessage());
            }
        }
    }
}
