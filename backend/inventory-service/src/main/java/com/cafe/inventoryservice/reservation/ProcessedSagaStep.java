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
 * Idempotency record for the reserve-stock saga command (plan section 4). Kafka is
 * at-least-once, so the same command can be redelivered; once an order_id row exists here,
 * a redelivered command is answered with the stored outcome instead of being reprocessed.
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
    @Column(name = "order_id")
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
