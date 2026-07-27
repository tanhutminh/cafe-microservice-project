package com.cafe.common.event;

import java.util.List;

/**
 * Saga command published by order-service (orchestrator) to Kafka topic
 * "inventory.reserve-stock.command", key = orderId. inventory-service consumes
 * it, attempts an all-or-nothing stock deduction, and replies on
 * "inventory.stock-reservation.reply" with an {@link InventoryStockReservationReply}.
 *
 * sagaAttemptId identifies this specific checkout attempt (fresh per attempt, not per order) —
 * it's the idempotency key inventory-service dedupes on, so a redelivered command is correctly
 * ignored while a genuinely new attempt for the same order (e.g. retried after a prior failure)
 * is evaluated fresh instead of replaying the old outcome.
 */
public record InventoryReserveStockCommand(Long orderId, String sagaAttemptId, List<OrderLineItem> items) {
}
