package com.cafe.inventoryservice.inbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The Transactional Inbox pattern's durable envelope for one inbound saga command.
 * {@code correlationId} (the Kafka Correlation Identifier header) is both the
 * primary key and the idempotency key: {@link com.cafe.inventoryservice.event.StockReservationListener}
 * inserts a row here and acks, without running any business logic; {@link InboxMessageProcessor}
 * - a separate, asynchronous worker - later claims PENDING rows and does the actual
 * reserve/commit/release step, atomically with the status transition below. {@code payload} is
 * the command's {@code List<OrderLineItem>} as JSON, since the different saga commands don't
 * share a common supertype to persist directly.
 */
@Entity
@Table(name = "inbox_messages")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class InboxMessage {

    @Id
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 30)
    private InboxMessageType messageType;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InboxStatus status;

    @Column(name = "result_success")
    private Boolean resultSuccess;

    @Column(name = "result_reason", length = 500)
    private String resultReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_reason", length = 500)
    private String errorReason;

    /** W3C traceparent string captured from the live Kafka consumer span when this row was
     *  persisted, restored into a child span by {@link InboxMessageProcessor} - see the
     *  distributed tracing docs on that class. Null when there was no live span to capture. */
    @Column(length = 64)
    private String traceparent;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }
}
