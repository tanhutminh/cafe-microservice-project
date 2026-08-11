package com.cafe.orderservice.outbox;

/** Which saga command or event an {@link OutboxMessage} carries - one per outbound topic. */
public enum OutboxMessageType {
    RESERVE_STOCK,
    COMMIT_STOCK,
    RELEASE_STOCK,
    ORDER_PAID
}
