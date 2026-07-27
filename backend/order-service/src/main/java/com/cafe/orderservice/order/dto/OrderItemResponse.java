package com.cafe.orderservice.order.dto;

import com.cafe.orderservice.order.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        Long menuItemId,
        String name,
        BigDecimal price,
        int quantity
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getMenuItemId(),
                item.getNameSnapshot(),
                item.getPriceSnapshot(),
                item.getQuantity()
        );
    }
}
