package com.cafe.inventoryservice.inbox;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.reservation.StockReservationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * The Transactional Inbox's asynchronous worker half (M4), driven on a schedule by
 * {@link InboxPoller}. {@link #claimBatch()} locks and flips a PENDING batch to PROCESSING in
 * one short transaction (see {@link InboxMessageRepository#lockNextByStatus} for the SKIP
 * LOCKED claim); {@link #processOne} then runs a single message's business logic and status
 * transition atomically in its own transaction, so the poller can isolate one message's failure
 * from the rest of the batch. A row that stays PROCESSING because the process crashes
 * mid-message (rather than throwing) is not reclaimed - out of scope for this pass, and a small
 * exposure window since claim-then-process happens within the same poll cycle.
 */
@Service
public class InboxMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxMessageProcessor.class);

    /** Also used by StockReservationListener to resend a stored reply on a duplicate command. */
    public static final String RESERVATION_REPLY_TOPIC = "inventory.stock-reservation.reply";
    public static final String COMMIT_REPLY_TOPIC = "inventory.stock-commit.reply";

    private final InboxMessageRepository inboxMessageRepository;
    private final StockReservationService stockReservationService;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final InboxProperties properties;

    public InboxMessageProcessor(InboxMessageRepository inboxMessageRepository,
                                  StockReservationService stockReservationService,
                                  KafkaTemplate<Object, Object> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  InboxProperties properties) {
        this.inboxMessageRepository = inboxMessageRepository;
        this.stockReservationService = stockReservationService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public List<String> claimBatch() {
        List<InboxMessage> claimed = inboxMessageRepository.lockNextByStatus(
                InboxStatus.PENDING, PageRequest.of(0, properties.batchSize()));
        claimed.forEach(message -> message.setStatus(InboxStatus.PROCESSING));
        return claimed.stream().map(InboxMessage::getCorrelationId).collect(Collectors.toList());
    }

    /** Executes one already-claimed message's saga step and marks the outcome, atomically. */
    @Transactional
    public void processOne(String correlationId) {
        InboxMessage message = inboxMessageRepository.findById(correlationId)
                .orElseThrow(() -> new NoSuchElementException("Inbox message not found: " + correlationId));

        List<OrderLineItem> items = deserializeItems(message.getPayload());

        switch (message.getMessageType()) {
            case RESERVE_STOCK -> {
                InventoryStockReservationReply reply =
                        stockReservationService.reserve(message.getOrderId(), items);
                markProcessed(message, reply.success(), reply.reason());
                publishReply(RESERVATION_REPLY_TOPIC, message.getOrderId(), correlationId, reply);
            }
            case COMMIT_STOCK -> {
                InventoryStockCommitReply reply =
                        stockReservationService.commit(message.getOrderId(), items);
                markProcessed(message, reply.success(), reply.reason());
                publishReply(COMMIT_REPLY_TOPIC, message.getOrderId(), correlationId, reply);
            }
            case RELEASE_STOCK -> {
                stockReservationService.release(message.getOrderId(), items);
                markProcessed(message, true, null);
            }
        }

        log.info("Inbox processed: order {} correlation {} type {}",
                message.getOrderId(), correlationId, message.getMessageType());
    }

    /**
     * Runs in its own fresh transaction (REQUIRES_NEW) since it's only ever called from the
     * poller's catch block after processOne's transaction has already rolled back - the message
     * row itself must survive that rollback so the failure can be recorded on it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String correlationId, String reason) {
        inboxMessageRepository.findById(correlationId).ifPresent(message -> {
            int attempts = message.getAttemptCount() + 1;
            message.setAttemptCount(attempts);
            message.setErrorReason(reason);
            message.setStatus(attempts < properties.maxAttempts() ? InboxStatus.PENDING : InboxStatus.FAILED);
            log.warn("Inbox attempt {} failed for correlation {} (status now {}): {}",
                    attempts, correlationId, message.getStatus(), reason);
        });
    }

    private void markProcessed(InboxMessage message, boolean success, String reason) {
        message.setStatus(InboxStatus.PROCESSED);
        message.setResultSuccess(success);
        message.setResultReason(reason);
        message.setProcessedAt(Instant.now());
    }

    private void publishReply(String topic, Long orderId, String correlationId, Object reply) {
        Message<Object> message = MessageBuilder.withPayload(reply)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .setHeader(KafkaHeaders.KEY, String.valueOf(orderId))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);
    }

    private List<OrderLineItem> deserializeItems(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<List<OrderLineItem>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize inbox payload", e);
        }
    }
}
