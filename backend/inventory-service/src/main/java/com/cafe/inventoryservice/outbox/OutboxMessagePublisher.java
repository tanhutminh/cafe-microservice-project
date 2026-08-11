package com.cafe.inventoryservice.outbox;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.inventoryservice.inbox.InboxMessageProcessor;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The Transactional Outbox's asynchronous relay half, driven on a schedule by
 * {@link OutboxPoller} - the send-side mirror of this service's own InboxMessageProcessor.
 * {@link #claimBatch()} locks and flips a PENDING batch to PROCESSING in one short transaction
 * (see {@link OutboxMessageRepository#lockNextByStatus} for the SKIP LOCKED claim);
 * {@link #publishOne} then sends a single message and blocks on Kafka's send future
 * (app.outbox.publish-timeout) so the PUBLISHED status transition only ever commits once the
 * broker has actually acknowledged the record. A row that stays PROCESSING because the process
 * crashes after the broker ack but before the commit is not reclaimed - the same small,
 * accepted exposure window InboxPoller's Javadoc documents for its own claim-then-process cycle.
 */
@Service
public class OutboxMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxMessagePublisher.class);

    private final OutboxMessageRepository outboxMessageRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    public OutboxMessagePublisher(OutboxMessageRepository outboxMessageRepository,
                                   KafkaTemplate<Object, Object> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   OutboxProperties properties) {
        this.outboxMessageRepository = outboxMessageRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public List<Long> claimBatch() {
        List<OutboxMessage> claimed = outboxMessageRepository.lockNextByStatus(
                OutboxStatus.PENDING, PageRequest.of(0, properties.batchSize()));
        claimed.forEach(message -> message.setStatus(OutboxStatus.PROCESSING));
        return claimed.stream().map(OutboxMessage::getId).collect(Collectors.toList());
    }

    /** Sends one already-claimed message and marks it PUBLISHED, atomically, once the broker acks it. */
    @Transactional
    public void publishOne(Long id) {
        OutboxMessage outboxMessage = outboxMessageRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Outbox message not found: " + id));

        Message<Object> message = MessageBuilder.withPayload(deserializePayload(outboxMessage))
                .setHeader(KafkaHeaders.TOPIC, topicFor(outboxMessage.getMessageType()))
                .setHeader(KafkaHeaders.KEY, String.valueOf(outboxMessage.getOrderId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, outboxMessage.getCorrelationId())
                .build();

        try {
            kafkaTemplate.send(message).get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing outbox message " + id, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to publish outbox message " + id, e);
        }

        outboxMessage.setStatus(OutboxStatus.PUBLISHED);
        outboxMessage.setPublishedAt(Instant.now());
        log.info("Outbox published: order {} message {} type {}",
                outboxMessage.getOrderId(), id, outboxMessage.getMessageType());
    }

    /**
     * Runs in its own fresh transaction (REQUIRES_NEW) since it's only ever called from the
     * poller's catch block after publishOne's transaction has already rolled back - the message
     * row itself must survive that rollback so the failure can be recorded on it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long id, String reason) {
        outboxMessageRepository.findById(id).ifPresent(message -> {
            int attempts = message.getAttemptCount() + 1;
            message.setAttemptCount(attempts);
            message.setErrorReason(reason);
            message.setStatus(attempts < properties.maxAttempts() ? OutboxStatus.PENDING : OutboxStatus.FAILED);
            log.warn("Outbox attempt {} failed for message {} (status now {}): {}",
                    attempts, id, message.getStatus(), reason);
        });
    }

    private String topicFor(OutboxMessageType messageType) {
        return switch (messageType) {
            case RESERVATION_REPLY -> InboxMessageProcessor.RESERVATION_REPLY_TOPIC;
            case COMMIT_REPLY -> InboxMessageProcessor.COMMIT_REPLY_TOPIC;
        };
    }

    private Object deserializePayload(OutboxMessage outboxMessage) {
        try {
            Class<?> payloadType = switch (outboxMessage.getMessageType()) {
                case RESERVATION_REPLY -> InventoryStockReservationReply.class;
                case COMMIT_REPLY -> InventoryStockCommitReply.class;
            };
            return objectMapper.readValue(outboxMessage.getPayload(), payloadType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize outbox payload " + outboxMessage.getId(), e);
        }
    }
}
