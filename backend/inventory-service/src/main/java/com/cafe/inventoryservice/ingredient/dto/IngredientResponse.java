package com.cafe.inventoryservice.ingredient.dto;

import com.cafe.inventoryservice.ingredient.Ingredient;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record IngredientResponse(
        @Schema(example = "7") Long id,
        @Schema(example = "Milk") String name,
        @Schema(example = "liter") String unit,
        @Schema(example = "12.500") BigDecimal currentStock,
        @Schema(example = "2.000", description = "Low-stock alert threshold") BigDecimal minStock,
        @Schema(example = "false", description = "true when currentStock < minStock") boolean lowStock,
        @Schema(example = "true") boolean active
) {
    public static IngredientResponse from(Ingredient ingredient) {
        return new IngredientResponse(
                ingredient.getId(),
                ingredient.getName(),
                ingredient.getUnit(),
                ingredient.getCurrentStock(),
                ingredient.getMinStock(),
                ingredient.getCurrentStock().compareTo(ingredient.getMinStock()) < 0,
                ingredient.isActive()
        );
    }
}
