package com.cafe.inventoryservice.recipe;

import com.cafe.inventoryservice.recipe.dto.RecipeItemRequest;
import com.cafe.inventoryservice.recipe.dto.RecipeItemResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/menu-items/{menuItemId}/recipe")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    public List<RecipeItemResponse> findByMenuItemId(@PathVariable Long menuItemId) {
        return recipeService.findByMenuItemId(menuItemId).stream().map(RecipeItemResponse::from).toList();
    }

    @PutMapping
    public List<RecipeItemResponse> replace(@PathVariable Long menuItemId,
                                             @Valid @RequestBody List<@Valid RecipeItemRequest> lines) {
        return recipeService.replace(menuItemId, lines).stream().map(RecipeItemResponse::from).toList();
    }
}
