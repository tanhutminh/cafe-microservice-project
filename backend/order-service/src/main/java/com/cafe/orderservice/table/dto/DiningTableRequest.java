package com.cafe.orderservice.table.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record DiningTableRequest(
        @NotBlank @Schema(example = "Table 3") String tableNumber,
        @Min(1) @Schema(example = "4") int capacity,
        @Schema(example = "true") boolean active
) {
}
