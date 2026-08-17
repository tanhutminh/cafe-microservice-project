package com.cafe.inventoryservice.seed;

import com.cafe.inventoryservice.ingredient.Ingredient;
import com.cafe.inventoryservice.ingredient.IngredientRepository;
import com.cafe.inventoryservice.ingredient.IngredientService;
import com.cafe.inventoryservice.ingredient.dto.IngredientRequest;
import com.cafe.inventoryservice.recipe.MenuItemIngredientRepository;
import com.cafe.inventoryservice.recipe.RecipeService;
import com.cafe.inventoryservice.recipe.dto.RecipeItemRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dev-only bootstrap: seeds a full set of realistic ingredients and per-item recipes for
 * menu-service's MenuSeeder catalog (27 items), so the order saga has real recipes to
 * reserve/commit stock against instead of the "no recipe rows = always in stock" fallback. Mirrors
 * menu-service's MenuSeeder / auth-service's AdminSeeder pattern.
 *
 * <p>Ingredients are seeded additively (only names not already present get created), so it's safe
 * to run against a database that already has some ingredients from before - existing rows and their
 * current_stock/reservedQuantity are never touched. Recipes are only seeded if
 * menu_item_ingredients is completely empty, matching MenuSeeder's "seed once, admin edits after"
 * convention: RecipeEditor lets an admin rewrite any item's recipe afterward, and this seeder must
 * never overwrite that.
 *
 * <p>menuItemId below is a hardcoded 1-27 matching MenuSeeder's MENU list order exactly - the same
 * loose, no-real-FK cross-service coupling the schema comment on menu_item_ingredients already
 * documents, valid only because both seeders run once against an empty table on a fresh database.
 */
@Component
public class RecipeSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RecipeSeeder.class);

  private record SeedIngredient(
      String name, String unit, BigDecimal initialStock, BigDecimal minStock) {}

  private record SeedLine(String ingredientName, BigDecimal quantityRequired) {}

  private record SeedRecipe(long menuItemId, String menuItemName, List<SeedLine> lines) {}

  private static SeedLine line(String ingredientName, double quantityRequired) {
    return new SeedLine(ingredientName, BigDecimal.valueOf(quantityRequired));
  }

  // Cà phê hạt / Sữa tươi / Đường trắng might already exist if this runs against a database
  // that already has some ingredients - seeded here too (additively) so a fresh database
  // ends up with the same full set either way.
  private static final List<SeedIngredient> INGREDIENTS =
      List.of(
          new SeedIngredient("Cà phê hạt", "g", BigDecimal.valueOf(2000), BigDecimal.valueOf(300)),
          new SeedIngredient("Sữa tươi", "ml", BigDecimal.valueOf(5000), BigDecimal.valueOf(1000)),
          new SeedIngredient(
              "Đường trắng", "g", BigDecimal.valueOf(10000), BigDecimal.valueOf(3000)),
          new SeedIngredient("Sữa đặc", "ml", BigDecimal.valueOf(3000), BigDecimal.valueOf(500)),
          new SeedIngredient("Đường đen", "g", BigDecimal.valueOf(5000), BigDecimal.valueOf(1000)),
          new SeedIngredient("Trà đen", "g", BigDecimal.valueOf(500), BigDecimal.valueOf(100)),
          new SeedIngredient("Trà xanh", "g", BigDecimal.valueOf(500), BigDecimal.valueOf(100)),
          new SeedIngredient("Bột matcha", "g", BigDecimal.valueOf(300), BigDecimal.valueOf(50)),
          new SeedIngredient("Sả tươi", "g", BigDecimal.valueOf(200), BigDecimal.valueOf(50)),
          new SeedIngredient("Đào ngâm", "g", BigDecimal.valueOf(800), BigDecimal.valueOf(150)),
          new SeedIngredient("Vải thiều", "g", BigDecimal.valueOf(800), BigDecimal.valueOf(150)),
          new SeedIngredient("Trân châu", "g", BigDecimal.valueOf(1000), BigDecimal.valueOf(200)),
          new SeedIngredient("Cam tươi", "trái", BigDecimal.valueOf(60), BigDecimal.valueOf(15)),
          new SeedIngredient("Dưa hấu", "kg", BigDecimal.valueOf(10), BigDecimal.valueOf(2)),
          new SeedIngredient("Bơ sáp", "trái", BigDecimal.valueOf(20), BigDecimal.valueOf(5)),
          new SeedIngredient("Xoài chín", "trái", BigDecimal.valueOf(20), BigDecimal.valueOf(5)),
          new SeedIngredient("Sữa chua", "ml", BigDecimal.valueOf(2000), BigDecimal.valueOf(300)),
          new SeedIngredient("Kem tươi", "ml", BigDecimal.valueOf(1500), BigDecimal.valueOf(300)),
          new SeedIngredient("Bột cacao", "g", BigDecimal.valueOf(500), BigDecimal.valueOf(100)),
          new SeedIngredient(
              "Sốt caramel", "ml", BigDecimal.valueOf(1000), BigDecimal.valueOf(200)),
          new SeedIngredient("Bột mì", "g", BigDecimal.valueOf(3000), BigDecimal.valueOf(500)),
          new SeedIngredient("Bơ lạt", "g", BigDecimal.valueOf(1000), BigDecimal.valueOf(200)),
          new SeedIngredient("Trứng gà", "trứng", BigDecimal.valueOf(60), BigDecimal.valueOf(12)),
          new SeedIngredient("Phô mai kem", "g", BigDecimal.valueOf(1000), BigDecimal.valueOf(200)),
          new SeedIngredient(
              "Phô mai mascarpone", "g", BigDecimal.valueOf(600), BigDecimal.valueOf(100)),
          new SeedIngredient(
              "Bánh mì sandwich", "cái", BigDecimal.valueOf(30), BigDecimal.valueOf(5)),
          new SeedIngredient("Ức gà", "g", BigDecimal.valueOf(2000), BigDecimal.valueOf(400)),
          new SeedIngredient("Xà lách", "g", BigDecimal.valueOf(500), BigDecimal.valueOf(100)),
          new SeedIngredient("Cà chua", "g", BigDecimal.valueOf(500), BigDecimal.valueOf(100)),
          new SeedIngredient("Khoai tây", "g", BigDecimal.valueOf(5000), BigDecimal.valueOf(1000)),
          new SeedIngredient("Bánh mì que", "cái", BigDecimal.valueOf(40), BigDecimal.valueOf(8)),
          new SeedIngredient("Pate", "g", BigDecimal.valueOf(500), BigDecimal.valueOf(100)),
          new SeedIngredient("Chả lụa", "g", BigDecimal.valueOf(500), BigDecimal.valueOf(100)));

  // menuItemId 1-27, matching MenuSeeder.MENU's category-then-item insertion order exactly.
  private static final List<SeedRecipe> RECIPES =
      List.of(
          new SeedRecipe(1, "Cà phê đen đá", List.of(line("Cà phê hạt", 25))),
          new SeedRecipe(2, "Cà phê đen nóng", List.of(line("Cà phê hạt", 20))),
          new SeedRecipe(3, "Cà phê sữa đá", List.of(line("Cà phê hạt", 20), line("Sữa đặc", 30))),
          new SeedRecipe(
              4, "Cà phê sữa nóng", List.of(line("Cà phê hạt", 20), line("Sữa đặc", 30))),
          new SeedRecipe(5, "Bạc xỉu", List.of(line("Cà phê hạt", 15), line("Sữa đặc", 50))),
          new SeedRecipe(6, "Espresso", List.of(line("Cà phê hạt", 18))),
          new SeedRecipe(7, "Cappuccino", List.of(line("Cà phê hạt", 18), line("Sữa tươi", 120))),
          new SeedRecipe(8, "Latte", List.of(line("Cà phê hạt", 18), line("Sữa tươi", 180))),
          new SeedRecipe(
              9,
              "Trà đào cam sả",
              List.of(
                  line("Trà đen", 5),
                  line("Đào ngâm", 40),
                  line("Cam tươi", 0.5),
                  line("Sả tươi", 5),
                  line("Đường trắng", 10))),
          new SeedRecipe(10, "Trà vải", List.of(line("Trà xanh", 5), line("Vải thiều", 40))),
          new SeedRecipe(11, "Hồng trà", List.of(line("Trà đen", 8), line("Đường trắng", 10))),
          new SeedRecipe(
              12,
              "Trà sữa trân châu đường đen",
              List.of(
                  line("Trà đen", 6),
                  line("Sữa tươi", 100),
                  line("Trân châu", 50),
                  line("Đường đen", 20))),
          new SeedRecipe(
              13, "Trà sữa matcha", List.of(line("Bột matcha", 10), line("Sữa tươi", 150))),
          new SeedRecipe(14, "Nước cam ép", List.of(line("Cam tươi", 3))),
          new SeedRecipe(15, "Nước ép dưa hấu", List.of(line("Dưa hấu", 0.4))),
          new SeedRecipe(16, "Sinh tố bơ", List.of(line("Bơ sáp", 1), line("Sữa đặc", 40))),
          new SeedRecipe(17, "Sinh tố xoài", List.of(line("Xoài chín", 1), line("Sữa chua", 100))),
          new SeedRecipe(
              18,
              "Đá xay matcha",
              List.of(line("Bột matcha", 12), line("Sữa tươi", 150), line("Kem tươi", 30))),
          new SeedRecipe(
              19,
              "Đá xay socola",
              List.of(line("Bột cacao", 25), line("Sữa tươi", 150), line("Kem tươi", 30))),
          new SeedRecipe(
              20,
              "Đá xay caramel",
              List.of(
                  line("Cà phê hạt", 15),
                  line("Sốt caramel", 30),
                  line("Sữa tươi", 120),
                  line("Kem tươi", 30))),
          new SeedRecipe(21, "Bánh croissant", List.of(line("Bột mì", 80), line("Bơ lạt", 40))),
          new SeedRecipe(
              22,
              "Bánh tiramisu",
              List.of(
                  line("Phô mai mascarpone", 60),
                  line("Cà phê hạt", 10),
                  line("Trứng gà", 1),
                  line("Bột mì", 20))),
          new SeedRecipe(
              23,
              "Bánh cheesecake",
              List.of(line("Phô mai kem", 90), line("Bột mì", 15), line("Trứng gà", 1))),
          new SeedRecipe(
              24,
              "Bánh su kem",
              List.of(line("Bột mì", 30), line("Trứng gà", 1), line("Kem tươi", 50))),
          new SeedRecipe(
              25,
              "Sandwich gà",
              List.of(
                  line("Bánh mì sandwich", 1),
                  line("Ức gà", 80),
                  line("Xà lách", 15),
                  line("Cà chua", 20))),
          new SeedRecipe(26, "Khoai tây chiên", List.of(line("Khoai tây", 200))),
          new SeedRecipe(
              27,
              "Bánh mì que pate",
              List.of(line("Bánh mì que", 1), line("Pate", 30), line("Chả lụa", 20))));

  private final IngredientRepository ingredientRepository;
  private final IngredientService ingredientService;
  private final MenuItemIngredientRepository menuItemIngredientRepository;
  private final RecipeService recipeService;

  public RecipeSeeder(
      IngredientRepository ingredientRepository,
      IngredientService ingredientService,
      MenuItemIngredientRepository menuItemIngredientRepository,
      RecipeService recipeService) {
    this.ingredientRepository = ingredientRepository;
    this.ingredientService = ingredientService;
    this.menuItemIngredientRepository = menuItemIngredientRepository;
    this.recipeService = recipeService;
  }

  @Override
  public void run(ApplicationArguments args) {
    Map<String, Ingredient> byName = seedMissingIngredients();
    seedRecipesIfEmpty(byName);
  }

  private Map<String, Ingredient> seedMissingIngredients() {
    Map<String, Ingredient> byName =
        ingredientRepository.findAll().stream()
            .collect(Collectors.toMap(Ingredient::getName, i -> i, (a, b) -> a));

    int seeded = 0;
    for (SeedIngredient seed : INGREDIENTS) {
      if (byName.containsKey(seed.name())) {
        continue;
      }
      Ingredient created =
          ingredientService.create(
              new IngredientRequest(seed.name(), seed.unit(), seed.minStock(), true));
      ingredientService.stockIn(created.getId(), seed.initialStock());
      byName.put(seed.name(), created);
      seeded++;
    }

    if (seeded > 0) {
      log.info("Seeded {} new ingredients (dev only).", seeded);
    }
    return byName;
  }

  private void seedRecipesIfEmpty(Map<String, Ingredient> byName) {
    if (menuItemIngredientRepository.count() > 0) {
      return;
    }

    for (SeedRecipe recipe : RECIPES) {
      List<RecipeItemRequest> lines =
          recipe.lines().stream()
              .map(
                  l ->
                      new RecipeItemRequest(
                          byName.get(l.ingredientName()).getId(), l.quantityRequired()))
              .toList();
      recipeService.replace(recipe.menuItemId(), lines);
    }
    log.info("Seeded recipes (dev only) for {} menu items.", RECIPES.size());
  }
}
