package com.cafe.orderservice.saga;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Checkout saga orchestration bookkeeping — one row per order that has
 * ever started checkout. Not exposed via API; the order's own status/failureReason are
 * what clients poll. sagaId == orderId (order-service is the orchestrator for its own
 * aggregate, so no separate saga identity is needed).
 */
@Entity
@Table(name = "order_saga_state")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OrderSagaState {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    /**
     * The Kafka Correlation Identifier (KafkaHeaders.CORRELATION_ID) for the current checkout
     * attempt — fresh UUID each time startCheckout runs, distinct from orderId. Kafka
     * redelivery of the same command carries the same correlationId (correctly deduped by
     * inventory-service's idempotent receiver); a brand-new checkout click for the same order
     * after a prior failure gets a new one, so it's evaluated fresh instead of replaying the
     * old outcome. order-service also uses it to match an incoming reply to the attempt it's
     * currently waiting on — see OrderSagaStateService.shouldIgnoreReply.
     */
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaStep step;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** How many times OrderSagaReconciliationJob has re-published the command for this saga. */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (requestedAt == null) {
            requestedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
