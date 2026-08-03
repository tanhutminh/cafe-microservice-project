package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

public record UpdateOrderItemQuantityRequest(
        @Min(1) @Schema(example = "3", description = "New quantity for the line - use DELETE /items/{itemId} to remove it entirely instead of setting 0") int quantity
) {
}
