package com.cafe.common.event;

import java.util.List;

/**
 * Saga command published by order-service (orchestrator) to Kafka topic
 * "inventory.reserve-stock.command", key = orderId. inventory-service consumes
 * it, attempts an all-or-nothing stock deduction, and replies on
 * "inventory.stock-reservation.reply" with an {@link InventoryStockReservationReply}.
 *
 * The idempotency/correlation key for this attempt travels as the Kafka correlation id
 * header (KafkaHeaders.CORRELATION_ID — the Correlation Identifier pattern), not as a
 * payload field — it's message envelope metadata, not business data, so it stays out of
 * this record. See com.cafe.orderservice.saga.OrderCheckoutSaga for how it's set.
 */
public record InventoryReserveStockCommand(Long orderId, List<OrderLineItem> items) {
}
