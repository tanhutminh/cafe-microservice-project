package com.cafe.orderservice.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The Transactional Outbox pattern's durable envelope for one outbound Kafka message - the write
 * side's mirror of inventory-service's Transactional Inbox ({@code
 * com.cafe.inventoryservice.inbox.InboxMessage}). {@code OrderSaga} inserts a row here in the
 * *same* transaction as the order/saga-state change it belongs to, instead of calling KafkaTemplate
 * directly; {@link OutboxMessagePublisher} - a separate, asynchronous worker - later claims PENDING
 * rows and relays them to Kafka, atomically with the status transition below. Unlike InboxMessage
 * (where correlationId is both the primary key and the idempotency key for a single inbound
 * attempt), a single order produces many outbox rows over its lifetime, so this uses a generated
 * surrogate key instead. {@code payload} is the full type-specific command/event record as JSON
 * (not just line items) since the four message types don't share a common shape - {@link
 * OutboxMessagePublisher} deserializes it by {@code messageType}.
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

  /**
   * W3C traceparent string captured at enqueue time, restored into a child span by {@link
   * OutboxMessagePublisher} - see the distributed tracing docs on that class. Null when there was
   * no live span to capture (e.g. a scheduler-thread caller).
   */
  @Column(length = 64)
  private String traceparent;

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
