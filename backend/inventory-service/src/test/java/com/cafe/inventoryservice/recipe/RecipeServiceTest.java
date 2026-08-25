package com.cafe.inventoryservice.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.inventoryservice.ingredient.Ingredient;
import com.cafe.inventoryservice.ingredient.IngredientService;
import com.cafe.inventoryservice.recipe.dto.RecipeItemRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecipeServiceTest {

  private static final Long MENU_ITEM_ID = 23L;

  @Mock private MenuItemIngredientRepository menuItemIngredientRepository;
  @Mock private IngredientService ingredientService;

  private RecipeService service;

  @BeforeEach
  void setUp() {
    service = new RecipeService(menuItemIngredientRepository, ingredientService);
  }

  private Ingredient ingredient(Long id) {
    return Ingredient.builder()
        .id(id)
        .name("Ingredient " + id)
        .unit("g")
        .currentStock(BigDecimal.TEN)
        .minStock(BigDecimal.ZERO)
        .reservedQuantity(BigDecimal.ZERO)
        .active(true)
        .build();
  }

  /**
   * Verifies ingredients are batch-fetched with a single {@code findAllByIdAsMap} call covering
   * every line, not one lookup per line.
   */
  @Test
  void replace_deletesOldLinesThenBatchFetchesAllIngredientsInOneCallAndSavesEachLine() {
    RecipeItemRequest lineOne = new RecipeItemRequest(1L, new BigDecimal("15.000"));
    RecipeItemRequest lineTwo = new RecipeItemRequest(2L, new BigDecimal("90.000"));
    Ingredient ingredientOne = ingredient(1L);
    Ingredient ingredientTwo = ingredient(2L);
    when(ingredientService.findAllByIdAsMap(List.of(1L, 2L)))
        .thenReturn(Map.of(1L, ingredientOne, 2L, ingredientTwo));
    when(menuItemIngredientRepository.save(any(MenuItemIngredient.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    List<MenuItemIngredient> result = service.replace(MENU_ITEM_ID, List.of(lineOne, lineTwo));

    assertAll(
        () -> verify(menuItemIngredientRepository).deleteByMenuItemId(MENU_ITEM_ID),
        () -> verify(menuItemIngredientRepository).flush(),
        () -> verify(ingredientService, times(1)).findAllByIdAsMap(List.of(1L, 2L)),
        () -> assertThat(result).hasSize(2),
        () -> assertThat(result.get(0).getMenuItemId()).isEqualTo(MENU_ITEM_ID),
        () -> assertThat(result.get(0).getIngredient()).isSameAs(ingredientOne),
        () -> assertThat(result.get(0).getQuantityRequired()).isEqualTo(new BigDecimal("15.000")),
        () -> assertThat(result.get(1).getIngredient()).isSameAs(ingredientTwo),
        () -> assertThat(result.get(1).getQuantityRequired()).isEqualTo(new BigDecimal("90.000")));
  }

  @Test
  void replace_withNoLines_deletesOldRecipeAndSavesNothing() {
    when(ingredientService.findAllByIdAsMap(List.of())).thenReturn(Map.of());

    List<MenuItemIngredient> result = service.replace(MENU_ITEM_ID, List.of());

    assertAll(
        () -> verify(menuItemIngredientRepository).deleteByMenuItemId(MENU_ITEM_ID),
        () -> assertThat(result).isEmpty());
  }
}
