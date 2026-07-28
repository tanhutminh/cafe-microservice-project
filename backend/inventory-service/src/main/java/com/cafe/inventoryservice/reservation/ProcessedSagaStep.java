package com.cafe.inventoryservice.reservation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Idempotency record for the reserve-stock saga command (plan section 4). Keyed by
 * correlationId — the Kafka Correlation Identifier (KafkaHeaders.CORRELATION_ID), one per
 * checkout attempt rather than per order — so a redelivered Kafka command (same correlation
 * id) is correctly answered with the stored outcome, while a genuinely new checkout attempt
 * for the same order (e.g. retried after a prior failure) gets its own id and is evaluated
 * fresh instead of replaying a stale result.
 */
@Entity
@Table(name = "processed_saga_steps")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ProcessedSagaStep {

    @Id
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 30)
    private String step;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 500)
    private String reason;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }
}
