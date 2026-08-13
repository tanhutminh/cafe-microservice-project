package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MoveTableRequest(@NotNull @Positive @Schema(example = "5", description = "Must be AVAILABLE") Long tableId) {
}
