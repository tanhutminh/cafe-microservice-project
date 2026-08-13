package com.cafe.common.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Compensating command published by order-service to Kafka topic
 * "inventory.release-stock.command", key = orderId, when a CONFIRMED order (stock
 * already reserved) is cancelled before payment. Fire-and-forget: order-service does
 * not wait for a reply, unlike {@link InventoryReserveStockCommand}'s round trip.
 *
 * Validated at the Kafka consumption boundary (see StockReservationListener), not here at
 * construction — see {@link InventoryReserveStockCommand}'s Javadoc for why.
 */
public record InventoryReleaseStockCommand(@NotNull @Positive Long orderId, @NotEmpty @Valid List<OrderLineItem> items) {
}
