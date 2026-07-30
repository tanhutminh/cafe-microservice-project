package com.cafe.inventoryservice.ingredient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record IngredientRequest(
        @NotBlank @Schema(example = "Milk") String name,
        @NotBlank @Schema(example = "liter") String unit,
        @Schema(example = "true") boolean active
) {
}
