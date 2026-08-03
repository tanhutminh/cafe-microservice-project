package com.cafe.common.event;

/**
 * Reply published by inventory-service on Kafka topic "inventory.stock-commit.reply",
 * key = orderId, answering an {@link InventoryCommitStockCommand}. Mirrors
 * {@link InventoryStockReservationReply}'s shape but is kept as its own type since it's a
 * distinct event in the saga (the payment leg, not the reservation leg).
 */
public record InventoryStockCommitReply(Long orderId, boolean success, String reason) {

    public static InventoryStockCommitReply success(Long orderId) {
        return new InventoryStockCommitReply(orderId, true, null);
    }

    public static InventoryStockCommitReply failure(Long orderId, String reason) {
        return new InventoryStockCommitReply(orderId, false, reason);
    }
}
