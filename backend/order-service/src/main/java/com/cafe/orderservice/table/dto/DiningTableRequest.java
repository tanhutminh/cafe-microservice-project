package com.cafe.orderservice.table.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record DiningTableRequest(
        @NotBlank String tableNumber,
        @Min(1) int capacity,
        boolean active
) {
}
