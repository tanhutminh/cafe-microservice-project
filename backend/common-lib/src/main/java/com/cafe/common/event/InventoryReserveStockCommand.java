package com.cafe.common.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Saga command published by order-service (orchestrator) to Kafka topic
 * "inventory.reserve-stock.command", key = orderId. inventory-service consumes it, attempts an
 * all-or-nothing stock deduction, and replies on "inventory.stock-reservation.reply" with an {@link
 * InventoryStockReservationReply}.
 *
 * <p>The idempotency/correlation key for this attempt travels as the Kafka correlation id header
 * (KafkaHeaders.CORRELATION_ID — the Correlation Identifier pattern), not as a payload field — it's
 * message envelope metadata, not business data, so it stays out of this record. See
 * com.cafe.orderservice.saga.OrderSaga for how it's set.
 *
 * <p>Validated at the Kafka consumption boundary (see StockReservationListener), not here at
 * construction — these annotations declare the constraint, an explicit Validator.validate() call at
 * the point of use enforces it, same two-step shape as every REST DTO's @Valid.
 */
public record InventoryReserveStockCommand(
    @NotNull @Positive Long orderId, @NotEmpty @Valid List<OrderLineItem> items) {}
