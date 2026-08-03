package com.cafe.common.event;

import java.util.List;

/**
 * Compensating command published by order-service to Kafka topic
 * "inventory.release-stock.command", key = orderId, when a CONFIRMED order (stock
 * already reserved) is cancelled before payment. Fire-and-forget: order-service does
 * not wait for a reply, unlike {@link InventoryReserveStockCommand}'s round trip.
 */
public record InventoryReleaseStockCommand(Long orderId, List<OrderLineItem> items) {
}
