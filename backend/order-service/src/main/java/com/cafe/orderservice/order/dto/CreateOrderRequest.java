package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(@NotNull @Schema(example = "3") Long tableId) {
}
