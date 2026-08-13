package com.cafe.inventoryservice.event;

import com.cafe.common.event.InventoryCommitStockCommand;
import com.cafe.common.event.InventoryReleaseStockCommand;
import com.cafe.common.event.InventoryReserveStockCommand;
import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.inventoryservice.inbox.InboxMessage;
import com.cafe.inventoryservice.inbox.InboxMessageProcessor;
import com.cafe.inventoryservice.inbox.InboxMessageRepository;
import com.cafe.inventoryservice.inbox.InboxMessageType;
import com.cafe.inventoryservice.inbox.InboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Transactional Inbox pattern's receive side. Each listener method only persists the
 * command into {@code inbox_messages} (status PENDING) and acks; it never runs business logic
 * inline. {@link InboxMessageProcessor}, a separate asynchronous worker polled by InboxPoller,
 * does the actual reserve/commit/release step later. correlationId is the inbox's primary key,
 * so a redelivered command is naturally idempotent at receipt: if it's already queued or being
 * processed, this is a no-op; if it was already PROCESSED, the stored reply is resent here so a
 * lost-reply retry (see OrderCheckoutSaga.retryOrCompensate, which redelivers with the same
 * correlationId) still gets answered without re-running business logic.
 *
 * {@link #validate} rejects a structurally invalid command (null orderId, null/empty items,
 * a non-positive quantity - see the Bean Validation annotations on the command records
 * themselves) before it's ever enqueued into {@code inbox_messages}, the same way {@code @Valid}
 * guards a REST controller's {@code @RequestBody} - so InboxMessageProcessor's later,
 * asynchronous processing never has to handle invalid data. A thrown
 * {@link ConstraintViolationException} propagates out of the listener method like any other
 * exception, routing to the DLQ via KafkaErrorHandlingConfig exactly like a deserialization
 * failure would.
 */
@Component
public class StockReservationListener {

    private static final Logger log = LoggerFactory.getLogger(StockReservationListener.class);

    private final InboxMessageRepository inboxMessageRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final Validator validator;

    public StockReservationListener(InboxMessageRepository inboxMessageRepository,
                                     ObjectMapper objectMapper,
                                     KafkaTemplate<Object, Object> kafkaTemplate,
                                     Validator validator) {
        this.inboxMessageRepository = inboxMessageRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.validator = validator;
    }

    @KafkaListener(topics = "inventory.reserve-stock.command")
    @Transactional
    public void onReserveStockCommand(InventoryReserveStockCommand command,
                                       @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        validate(command);
        var existing = inboxMessageRepository.findById(correlationId);
        if (existing.isPresent()) {
            resendReplyIfProcessed(existing.get(), InboxMessageProcessor.RESERVATION_REPLY_TOPIC,
                    (orderId, success, reason) -> success
                            ? InventoryStockReservationReply.success(orderId)
                            : InventoryStockReservationReply.failure(orderId, reason));
            return;
        }

        enqueue(correlationId, command.orderId(), InboxMessageType.RESERVE_STOCK, command.items());
        log.info("Inbox: queued reserve-stock command for order {} correlation {}", command.orderId(), correlationId);
    }

    @KafkaListener(topics = "inventory.commit-stock.command")
    @Transactional
    public void onCommitStockCommand(InventoryCommitStockCommand command,
                                      @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        validate(command);
        var existing = inboxMessageRepository.findById(correlationId);
        if (existing.isPresent()) {
            resendReplyIfProcessed(existing.get(), InboxMessageProcessor.COMMIT_REPLY_TOPIC,
                    (orderId, success, reason) -> success
                            ? InventoryStockCommitReply.success(orderId)
                            : InventoryStockCommitReply.failure(orderId, reason));
            return;
        }

        enqueue(correlationId, command.orderId(), InboxMessageType.COMMIT_STOCK, command.items());
        log.info("Inbox: queued commit-stock command for order {} correlation {}", command.orderId(), correlationId);
    }

    @KafkaListener(topics = "inventory.release-stock.command")
    @Transactional
    public void onReleaseStockCommand(InventoryReleaseStockCommand command,
                                       @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        validate(command);
        if (inboxMessageRepository.existsById(correlationId)) {
            return;
        }

        enqueue(correlationId, command.orderId(), InboxMessageType.RELEASE_STOCK, command.items());
        log.info("Inbox: queued release-stock command for order {} correlation {}", command.orderId(), correlationId);
    }

    private void validate(Object command) {
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void enqueue(String correlationId, Long orderId, InboxMessageType type, Object items) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize inbox payload", e);
        }

        inboxMessageRepository.save(InboxMessage.builder()
                .correlationId(correlationId)
                .orderId(orderId)
                .messageType(type)
                .payload(payload)
                .status(InboxStatus.PENDING)
                .attemptCount(0)
                .build());
    }

    private void resendReplyIfProcessed(InboxMessage message, String topic, ReplyFactory replyFactory) {
        if (message.getStatus() != InboxStatus.PROCESSED) {
            return;
        }

        Object reply = replyFactory.build(message.getOrderId(), message.getResultSuccess(), message.getResultReason());
        Message<Object> kafkaMessage = MessageBuilder.withPayload(reply)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .setHeader(KafkaHeaders.KEY, String.valueOf(message.getOrderId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, message.getCorrelationId())
                .build();
        kafkaTemplate.send(kafkaMessage);

        log.info("Inbox: resent stored reply for order {} correlation {} (redelivered command)",
                message.getOrderId(), message.getCorrelationId());
    }

    @FunctionalInterface
    private interface ReplyFactory {
        Object build(Long orderId, boolean success, String reason);
    }
}
