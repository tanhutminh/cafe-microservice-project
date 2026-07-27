package com.cafe.inventoryservice.ingredient.dto;

import com.cafe.inventoryservice.ingredient.Ingredient;

import java.math.BigDecimal;

public record IngredientResponse(
        Long id,
        String name,
        String unit,
        BigDecimal currentStock,
        boolean active
) {
    public static IngredientResponse from(Ingredient ingredient) {
        return new IngredientResponse(
                ingredient.getId(),
                ingredient.getName(),
                ingredient.getUnit(),
                ingredient.getCurrentStock(),
                ingredient.isActive()
        );
    }
}
