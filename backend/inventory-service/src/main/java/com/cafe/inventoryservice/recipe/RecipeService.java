package com.cafe.inventoryservice.recipe;

import com.cafe.inventoryservice.ingredient.IngredientService;
import com.cafe.inventoryservice.recipe.dto.RecipeItemRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RecipeService {

    private final MenuItemIngredientRepository menuItemIngredientRepository;
    private final IngredientService ingredientService;

    public RecipeService(MenuItemIngredientRepository menuItemIngredientRepository, IngredientService ingredientService) {
        this.menuItemIngredientRepository = menuItemIngredientRepository;
        this.ingredientService = ingredientService;
    }

    public List<MenuItemIngredient> findByMenuItemId(Long menuItemId) {
        return menuItemIngredientRepository.findByMenuItemIdWithIngredient(menuItemId);
    }

    /** Replaces the whole recipe for a menu item with the given lines — simpler than per-line CRUD. */
    @Transactional
    public List<MenuItemIngredient> replace(Long menuItemId, List<RecipeItemRequest> lines) {
        menuItemIngredientRepository.deleteByMenuItemId(menuItemId);
        menuItemIngredientRepository.flush();

        return lines.stream()
                .map(line -> menuItemIngredientRepository.save(MenuItemIngredient.builder()
                        .menuItemId(menuItemId)
                        .ingredient(ingredientService.findById(line.ingredientId()))
                        .quantityRequired(line.quantityRequired())
                        .build()))
                .toList();
    }
}
