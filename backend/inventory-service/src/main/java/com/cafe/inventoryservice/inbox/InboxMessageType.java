package com.cafe.inventoryservice.inbox;

/** Which saga command an {@link InboxMessage} carries - one per inventory command topic. */
public enum InboxMessageType {
    RESERVE_STOCK,
    COMMIT_STOCK,
    RELEASE_STOCK
}
