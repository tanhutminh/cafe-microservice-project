package com.cafe.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Final domain event, published by order-service to Kafka topic "order.paid"
 * only after the checkout saga reaches COMPLETED (stock reservation confirmed).
 * report-service is the sole consumer — a plain analytics subscriber, not a
 * saga participant, since a report-service failure never needs to roll back
 * an already-settled order.
 */
public record OrderPaidEvent(
        Long orderId,
        Long tableId,
        Instant closedAt,
        List<OrderLineItem> items,
        BigDecimal grandTotal,
        String paymentMethod
) {
}
