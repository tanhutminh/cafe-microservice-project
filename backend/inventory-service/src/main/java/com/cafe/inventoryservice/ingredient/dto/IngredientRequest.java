package com.cafe.inventoryservice.ingredient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record IngredientRequest(
        @NotBlank @Schema(example = "Milk") String name,
        @NotBlank @Size(max = 20) @Schema(example = "liter") String unit,
        @NotNull @DecimalMin("0") @Schema(example = "2.000", description = "Low-stock alert threshold") BigDecimal minStock,
        @Schema(example = "true") boolean active
) {
}
