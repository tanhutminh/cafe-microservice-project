package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddOrderItemRequest(
        @NotNull @Positive @Schema(example = "12") Long menuItemId,
        @Min(1) @Schema(example = "2") int quantity
) {
}
