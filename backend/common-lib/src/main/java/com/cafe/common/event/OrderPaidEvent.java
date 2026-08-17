package com.cafe.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Final domain event, published by order-service to Kafka topic "order.paid" only after the order
 * saga reaches COMPLETED (payment leg's stock commit confirmed). No consumer yet - report-service
 * (still a scaffolded stub, see README) is the intended eventual reader, as a plain analytics
 * subscriber rather than a saga participant, since a report-service failure would never need to
 * roll back an already-settled order.
 */
public record OrderPaidEvent(
    Long orderId,
    Long tableId,
    Instant closedAt,
    List<OrderLineItem> items,
    BigDecimal grandTotal,
    String paymentMethod) {}
