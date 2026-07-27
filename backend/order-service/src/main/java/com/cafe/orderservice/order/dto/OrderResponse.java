package com.cafe.orderservice.order.dto;

import com.cafe.orderservice.order.Order;
import com.cafe.orderservice.order.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        Long tableId,
        String tableNumber,
        OrderStatus status,
        String paymentMethod,
        String failureReason,
        List<OrderItemResponse> items,
        BigDecimal grandTotal,
        Instant createdAt,
        Instant closedAt
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
