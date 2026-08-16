package com.cafe.inventoryservice.inbox;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.outbox.OutboxMessage;
import com.cafe.inventoryservice.outbox.OutboxMessageRepository;
import com.cafe.inventoryservice.outbox.OutboxMessageType;
import com.cafe.inventoryservice.outbox.OutboxStatus;
import com.cafe.inventoryservice.reservation.StockReservationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * The Transactional Inbox's asynchronous worker half, driven on a schedule by
 * {@link InboxPoller}. {@link #claimBatch()} locks and flips a PENDING batch to PROCESSING in
 * one short transaction (see {@link InboxMessageRepository#lockNextByStatus} for the SKIP
 * LOCKED claim); {@link #processOne} then runs a single message's business logic and status
 * transition atomically in its own transaction, so the poller can isolate one message's failure
 * from the rest of the batch. A row that stays PROCESSING because the process crashes
 * mid-message (rather than throwing) is not reclaimed - a known limitation, with a small
 * exposure window since claim-then-process happens within the same poll cycle.
 *
 * Replies are queued through the Transactional Outbox (see the outbox package) rather than sent
 * live: {@link #publishReply} writes an {@link OutboxMessage} row in the same transaction as the
 * stock mutation + status transition above, instead of calling KafkaTemplate directly, closing
 * the dual-write gap where a crash after commit but before the send would silently lose a reply
 * order-service is waiting on.
 *
 * <p>Distributed tracing: this runs on the poller's own thread, disconnected from the Kafka
 * consumer thread that received the command and persisted the {@link InboxMessage} row.
 * {@link #processOne} restores the {@code traceparent} string captured at receipt time into a
 * child span and keeps it current for the whole method body, so {@link #publishReply}'s own
 * capture records *this* span - not the original inbound one - onto the outgoing
 * {@link OutboxMessage}, closing the loop back to order-service.
 */
@Service
public class InboxMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxMessageProcessor.class);

    /** Also used by StockReservationListener to resend a stored reply on a duplicate command,
     *  and by OutboxMessagePublisher to map a queued reply's type back to its topic. */
    public static final String RESERVATION_REPLY_TOPIC = "inventory.stock-reservation.reply";
    public static final String COMMIT_REPLY_TOPIC = "inventory.stock-commit.reply";

    private final InboxMessageRepository inboxMessageRepository;
    private final StockReservationService stockReservationService;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ObjectMapper objectMapper;
    private final InboxProperties properties;
    private final Tracer tracer;
    private final Propagator propagator;

    public InboxMessageProcessor(InboxMessageRepository inboxMessageRepository,
                                  StockReservationService stockReservationService,
                                  OutboxMessageRepository outboxMessageRepository,
                                  ObjectMapper objectMapper,
                                  InboxProperties properties,
                                  Tracer tracer,
                                  Propagator propagator) {
        this.inboxMessageRepository = inboxMessageRepository;
        this.stockReservationService = stockReservationService;
        this.outboxMessageRepository = outboxMessageRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.tracer = tracer;
        this.propagator = propagator;
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

        Span span = startChildSpan("inbox-process", message.getTraceparent())
                .tag("inbox.correlation.id", correlationId)
                .tag("inbox.message.type", message.getMessageType().name())
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            List<OrderLineItem> items = deserializeItems(message.getPayload());

            switch (message.getMessageType()) {
                case RESERVE_STOCK -> {
                    InventoryStockReservationReply reply =
                            stockReservationService.reserve(message.getOrderId(), items);
                    markProcessed(message, reply.success(), reply.reason());
                    publishReply(OutboxMessageType.RESERVATION_REPLY, message.getOrderId(), correlationId, reply);
                }
                case COMMIT_STOCK -> {
                    InventoryStockCommitReply reply =
                            stockReservationService.commit(message.getOrderId(), items);
                    markProcessed(message, reply.success(), reply.reason());
                    publishReply(OutboxMessageType.COMMIT_REPLY, message.getOrderId(), correlationId, reply);
                }
                case RELEASE_STOCK -> {
                    stockReservationService.release(message.getOrderId(), items);
                    markProcessed(message, true, null);
                }
            }
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }

        log.info("Inbox processed: order {} correlation {} type {}",
                message.getOrderId(), correlationId, message.getMessageType());
    }

    /** Restores a stored traceparent into a new child span, or starts a fresh root span when
     *  there wasn't one to restore. */
    private Span.Builder startChildSpan(String name, String traceparent) {
        if (traceparent == null) {
            return tracer.spanBuilder().name(name);
        }
        return propagator.extract(Map.of("traceparent", traceparent), Map::get).name(name);
    }

    /** Captures the current W3C traceparent (the inbox-process span above, while it's in scope)
     *  so {@link com.cafe.inventoryservice.outbox.OutboxMessagePublisher} can restore it into a
     *  child span on its own poller thread - see this class's Javadoc for the full picture. */
    private String captureTraceParent() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get("traceparent");
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

    private void publishReply(OutboxMessageType type, Long orderId, String correlationId, Object reply) {
        OutboxMessage outboxMessage = OutboxMessage.builder()
                .orderId(orderId)
                .messageType(type)
                .correlationId(correlationId)
                .payload(serialize(reply))
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .traceparent(captureTraceParent())
                .build();
        outboxMessageRepository.save(outboxMessage);
    }

    private String serialize(Object reply) {
        try {
            return objectMapper.writeValueAsString(reply);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    private List<OrderLineItem> deserializeItems(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<List<OrderLineItem>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize inbox payload", e);
        }
    }
}
