package com.cafe.orderservice.order.dto;

import com.cafe.orderservice.order.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record OrderItemResponse(
        @Schema(example = "42") Long id,
        @Schema(example = "12") Long menuItemId,
        @Schema(example = "Cappuccino", description = "Snapshotted at add-time - won't change even if the menu item is renamed later") String name,
        @Schema(example = "45000", description = "Snapshotted at add-time - won't change even if the price changes later") BigDecimal price,
        @Schema(example = "2") int quantity
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
