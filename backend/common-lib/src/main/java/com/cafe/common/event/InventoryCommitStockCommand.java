package com.cafe.common.event;

import java.util.List;

/**
 * Saga command published by order-service to Kafka topic "inventory.commit-stock.command",
 * key = orderId — the payment leg's inventory-side step. inventory-service turns the earlier
 * soft hold (from {@link InventoryReserveStockCommand}) into a real deduction: currentStock
 * decreases and reservedQuantity is released, in the same transaction. Does not re-validate
 * availability - that already happened at reservation time.
 */
public record InventoryCommitStockCommand(Long orderId, List<OrderLineItem> items) {
}
