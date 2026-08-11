package com.cafe.inventoryservice.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The Transactional Outbox pattern's durable envelope for one outbound Kafka reply - the
 * send-side counterpart to {@code com.cafe.inventoryservice.inbox.InboxMessage} on this same
 * service. {@code InboxMessageProcessor} inserts a row here in the *same* transaction as the
 * stock mutation + InboxMessage status transition it belongs to, instead of calling
 * KafkaTemplate directly; {@link OutboxMessagePublisher} - a separate, asynchronous worker -
 * later claims PENDING rows and relays them to Kafka, atomically with the status transition
 * below. Uses a generated surrogate key (not correlationId as PK) since, unlike InboxMessage,
 * nothing here needs receipt-time idempotency - inventory-service is the sender, not the
 * receiver, on this leg.
 */
@Entity
@Table(name = "outbox_messages")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 30)
    private OutboxMessageType messageType;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_reason", length = 500)
    private String errorReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
