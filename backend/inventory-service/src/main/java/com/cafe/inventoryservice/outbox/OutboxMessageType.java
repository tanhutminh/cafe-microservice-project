package com.cafe.inventoryservice.outbox;

/** Which saga reply an {@link OutboxMessage} carries - one per outbound reply topic. Release
 *  has no reply leg, so it never produces an outbox row (same asymmetry as InboxMessageType). */
public enum OutboxMessageType {
    RESERVATION_REPLY,
    COMMIT_REPLY
}
