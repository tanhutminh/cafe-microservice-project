package com.cafe.common.event;

/**
 * Reply published by inventory-service on Kafka topic "inventory.stock-reservation.reply",
 * key = orderId. On success == false, order-service (the saga orchestrator) compensates
 * by reverting the order back to OPEN with {@code reason} surfaced to the POS UI — see
 * plan section 4. inventory-service never partially deducts: either every line had enough
 * stock and all were deducted, or none were.
 *
 * sagaAttemptId echoes back the command's attempt id, so order-service can tell a reply for
 * the attempt it's currently waiting on apart from a late reply belonging to an attempt it has
 * already moved on from.
 */
public record InventoryStockReservationReply(Long orderId, String sagaAttemptId, boolean success, String reason) {

    public static InventoryStockReservationReply success(Long orderId, String sagaAttemptId) {
        return new InventoryStockReservationReply(orderId, sagaAttemptId, true, null);
    }

    public static InventoryStockReservationReply failure(Long orderId, String sagaAttemptId, String reason) {
        return new InventoryStockReservationReply(orderId, sagaAttemptId, false, reason);
    }
}
