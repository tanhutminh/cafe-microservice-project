package com.cafe.inventoryservice.inbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InboxMessageTest {

    @Test
    void onCreate_defaultsReceivedAtWhenNotSet() {
        InboxMessage message = InboxMessage.builder()
                .correlationId("corr-1")
                .orderId(1L)
                .messageType(InboxMessageType.RESERVE_STOCK)
                .payload("[]")
                .status(InboxStatus.PENDING)
                .attemptCount(0)
                .build();

        message.onCreate();

        assertThat(message.getReceivedAt()).isNotNull();
    }

    @Test
    void onCreate_leavesExplicitReceivedAtUntouched() {
        Instant explicit = Instant.parse("2026-01-01T00:00:00Z");
        InboxMessage message = InboxMessage.builder()
                .correlationId("corr-1")
                .orderId(1L)
                .messageType(InboxMessageType.RESERVE_STOCK)
                .payload("[]")
                .status(InboxStatus.PENDING)
                .attemptCount(0)
                .receivedAt(explicit)
                .build();

        message.onCreate();

        assertThat(message.getReceivedAt()).isEqualTo(explicit);
    }
}
