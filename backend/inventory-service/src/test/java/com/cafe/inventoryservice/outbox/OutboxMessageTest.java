package com.cafe.inventoryservice.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMessageTest {

    @Test
    void onCreate_defaultsCreatedAtWhenNotSet() {
        OutboxMessage message = OutboxMessage.builder()
                .orderId(1L)
                .messageType(OutboxMessageType.RESERVATION_REPLY)
                .correlationId("corr-1")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .build();

        message.onCreate();

        assertThat(message.getCreatedAt()).isNotNull();
    }

    @Test
    void onCreate_leavesExplicitCreatedAtUntouched() {
        Instant explicit = Instant.parse("2026-01-01T00:00:00Z");
        OutboxMessage message = OutboxMessage.builder()
                .orderId(1L)
                .messageType(OutboxMessageType.RESERVATION_REPLY)
                .correlationId("corr-1")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .createdAt(explicit)
                .build();

        message.onCreate();

        assertThat(message.getCreatedAt()).isEqualTo(explicit);
    }
}
