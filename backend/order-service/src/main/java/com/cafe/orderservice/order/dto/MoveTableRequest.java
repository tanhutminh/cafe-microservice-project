package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record MoveTableRequest(@NotNull @Schema(example = "5", description = "Must be AVAILABLE") Long tableId) {
}
