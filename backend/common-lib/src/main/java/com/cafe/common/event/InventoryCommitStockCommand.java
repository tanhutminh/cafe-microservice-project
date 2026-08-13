package com.cafe.common.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Saga command published by order-service to Kafka topic "inventory.commit-stock.command",
 * key = orderId — the payment leg's inventory-side step. inventory-service turns the earlier
 * soft hold (from {@link InventoryReserveStockCommand}) into a real deduction: currentStock
 * decreases and reservedQuantity is released, in the same transaction. Does not re-validate
 * availability - that already happened at reservation time.
 *
 * Validated at the Kafka consumption boundary (see StockReservationListener), not here at
 * construction — see {@link InventoryReserveStockCommand}'s Javadoc for why.
 */
public record InventoryCommitStockCommand(@NotNull @Positive Long orderId, @NotEmpty @Valid List<OrderLineItem> items) {
}
