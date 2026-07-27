package com.cafe.inventoryservice.ingredient.dto;

import jakarta.validation.constraints.NotBlank;

public record IngredientRequest(@NotBlank String name, @NotBlank String unit, boolean active) {
}
