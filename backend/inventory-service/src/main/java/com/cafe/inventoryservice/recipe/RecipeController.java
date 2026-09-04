package com.cafe.inventoryservice.recipe;

import com.cafe.inventoryservice.recipe.dto.RecipeItemRequest;
import com.cafe.inventoryservice.recipe.dto.RecipeItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/menu-items/{menuItemId}/recipe")
@Tag(
    name = "Recipes",
    description = "Which ingredients (and how much) each menu item consumes on checkout")
public class RecipeController {

  private final RecipeService recipeService;

  public RecipeController(RecipeService recipeService) {
    this.recipeService = recipeService;
  }

  @GetMapping
  @Operation(
      summary =
          "Get a menu item's recipe (empty list if it has none - such items are always treated as in-stock)")
  public List<RecipeItemResponse> findAllByMenuItemId(
      @Parameter(
              description =
                  "The menu item's id (owned by menu-service - just an application-level reference here)",
              example = "12")
          @PathVariable
          @Positive
          Long menuItemId) {
    return recipeService.findAllByMenuItemId(menuItemId).stream()
        .map(RecipeItemResponse::from)
        .toList();
  }

  @PutMapping
  @Operation(
      summary = "Replace a menu item's whole recipe (ADMIN only)",
      description =
          "This is a full replace, not a per-line patch: send every line the recipe "
              + "should have, including ones you're keeping unchanged. Any line not in the "
              + "request body is deleted. Sending an empty list clears the recipe entirely, "
              + "making the item always-in-stock again.")
  public List<RecipeItemResponse> replace(
      @Parameter(
              description =
                  "The menu item's id (owned by menu-service - just an application-level reference here)",
              example = "12")
          @PathVariable
          @Positive
          Long menuItemId,
      @Valid @RequestBody @Size(max = 50) List<@Valid RecipeItemRequest> lines) {
    return recipeService.replace(menuItemId, lines).stream().map(RecipeItemResponse::from).toList();
  }
}
