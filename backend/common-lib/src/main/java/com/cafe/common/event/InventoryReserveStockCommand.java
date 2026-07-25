package com.cafe.common.event;

import java.util.List;

/**
 * Saga command published by order-service (orchestrator) to Kafka topic
 * "inventory.reserve-stock.command", key = orderId. inventory-service consumes
 * it, attempts an all-or-nothing stock deduction, and replies on
 * "inventory.stock-reservation.reply" with an {@link InventoryStockReservationReply}.
 */
public record InventoryReserveStockCommand(Long orderId, List<OrderLineItem> items) {
}
