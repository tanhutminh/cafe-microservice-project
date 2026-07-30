package com.cafe.inventoryservice.recipe.dto;

import com.cafe.inventoryservice.recipe.MenuItemIngredient;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record RecipeItemResponse(
        @Schema(example = "7") Long ingredientId,
        @Schema(example = "Milk") String ingredientName,
        @Schema(example = "liter") String unit,
        @Schema(example = "0.200") BigDecimal quantityRequired
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
