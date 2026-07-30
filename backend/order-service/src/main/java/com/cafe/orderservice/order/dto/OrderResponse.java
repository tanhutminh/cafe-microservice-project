package com.cafe.orderservice.order.dto;

import com.cafe.orderservice.order.Order;
import com.cafe.orderservice.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        @Schema(example = "101") Long id,
        @Schema(example = "3") Long tableId,
        @Schema(example = "Table 3") String tableNumber,
        @Schema(example = "PAID", description = "OPEN -> PENDING_CONFIRMATION (mid-checkout-saga) -> PAID, or back to OPEN with failureReason set if the saga couldn't reserve stock") OrderStatus status,
        @Schema(example = "CASH") String paymentMethod,
        @Schema(example = "Insufficient stock: Milk", description = "Only set when the checkout saga compensated the order back to OPEN") String failureReason,
        List<OrderItemResponse> items,
        @Schema(example = "90000") BigDecimal grandTotal,
        @Schema(example = "2026-07-28T18:41:46.901862Z") Instant createdAt,
        @Schema(example = "2026-07-28T18:45:12.100Z") Instant closedAt
) {
    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().map(OrderItemResponse::from).toList();
        BigDecimal grandTotal = items.stream()
                .map(i -> i.price().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(
                order.getId(),
                order.getTable().getId(),
                order.getTable().getTableNumber(),
                order.getStatus(),
                order.getPaymentMethod(),
                order.getFailureReason(),
                items,
                grandTotal,
                order.getCreatedAt(),
                order.getClosedAt()
        );
    }
}
