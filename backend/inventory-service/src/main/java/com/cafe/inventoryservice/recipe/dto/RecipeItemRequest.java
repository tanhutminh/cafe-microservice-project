package com.cafe.inventoryservice.recipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record RecipeItemRequest(
        @NotNull @Positive @Schema(example = "7") Long ingredientId,
        @NotNull @DecimalMin(value = "0.001") @Schema(example = "0.200", description = "How much of the ingredient's unit one serving consumes") BigDecimal quantityRequired
) {
}
