package com.cafe.inventoryservice.recipe.dto;

import com.cafe.inventoryservice.recipe.MenuItemIngredient;

import java.math.BigDecimal;

public record RecipeItemResponse(
        Long ingredientId,
        String ingredientName,
        String unit,
        BigDecimal quantityRequired
) {
    public static RecipeItemResponse from(MenuItemIngredient recipeItem) {
        return new RecipeItemResponse(
                recipeItem.getIngredient().getId(),
                recipeItem.getIngredient().getName(),
                recipeItem.getIngredient().getUnit(),
                recipeItem.getQuantityRequired()
        );
    }
}
