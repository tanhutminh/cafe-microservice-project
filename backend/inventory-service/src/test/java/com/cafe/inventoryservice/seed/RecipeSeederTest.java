package com.cafe.inventoryservice.seed;

import com.cafe.inventoryservice.ingredient.Ingredient;
import com.cafe.inventoryservice.ingredient.IngredientRepository;
import com.cafe.inventoryservice.ingredient.IngredientService;
import com.cafe.inventoryservice.ingredient.dto.IngredientRequest;
import com.cafe.inventoryservice.recipe.MenuItemIngredientRepository;
import com.cafe.inventoryservice.recipe.RecipeService;
import com.cafe.inventoryservice.recipe.dto.RecipeItemRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipeSeederTest {

    @Mock
    private IngredientRepository ingredientRepository;
    @Mock
    private IngredientService ingredientService;
    @Mock
    private MenuItemIngredientRepository menuItemIngredientRepository;
    @Mock
    private RecipeService recipeService;

    private RecipeSeeder seeder;
    private final AtomicLong nextId = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        seeder = new RecipeSeeder(ingredientRepository, ingredientService, menuItemIngredientRepository, recipeService);
    }

    private void stubIngredientCreation() {
        when(ingredientService.create(any(IngredientRequest.class))).thenAnswer(invocation -> {
            IngredientRequest request = invocation.getArgument(0);
            return Ingredient.builder()
                    .id(nextId.getAndIncrement())
                    .name(request.name())
                    .unit(request.unit())
                    .currentStock(BigDecimal.ZERO)
                    .minStock(request.minStock())
                    .reservedQuantity(BigDecimal.ZERO)
                    .active(true)
                    .build();
        });
    }

    @Test
    void run_onEmptyDatabase_seedsEveryIngredientAndEveryRecipe() {
        when(ingredientRepository.findAll()).thenReturn(List.of());
        stubIngredientCreation();
        when(menuItemIngredientRepository.count()).thenReturn(0L);

        seeder.run(null);

        ArgumentCaptor<IngredientRequest> createCaptor = ArgumentCaptor.forClass(IngredientRequest.class);
        verify(ingredientService, times(33)).create(createCaptor.capture());
        assertThat(createCaptor.getAllValues())
                .extracting(IngredientRequest::name)
                .contains("Cà phê hạt", "Sữa tươi", "Đường trắng", "Đường đen", "Chả lụa");
        // Every created ingredient gets an initial stock-in, not a raw currentStock write.
        verify(ingredientService, times(33)).stockIn(anyLong(), any(BigDecimal.class));

        verify(recipeService, times(27)).replace(anyLong(), any());
    }

    @Test
    void run_whenIngredientAlreadyExists_doesNotRecreateIt() {
        Ingredient existing = Ingredient.builder()
                .id(99L).name("Cà phê hạt").unit("g")
                .currentStock(BigDecimal.valueOf(835)).minStock(BigDecimal.valueOf(300))
                .reservedQuantity(BigDecimal.ZERO).active(true).build();
        when(ingredientRepository.findAll()).thenReturn(List.of(existing));
        stubIngredientCreation();
        when(menuItemIngredientRepository.count()).thenReturn(0L);

        seeder.run(null);

        // 33 total ingredients minus the 1 that already existed.
        verify(ingredientService, times(32)).create(any(IngredientRequest.class));
        verify(ingredientService, never()).create(argThatNameIs("Cà phê hạt"));
    }

    @Test
    void run_whenRecipesAlreadyExist_doesNotReplaceAny() {
        when(ingredientRepository.findAll()).thenReturn(List.of());
        stubIngredientCreation();
        when(menuItemIngredientRepository.count()).thenReturn(4L);

        seeder.run(null);

        verify(recipeService, never()).replace(anyLong(), any());
    }

    @Test
    void run_resolvesRecipeLinesToTheSeededIngredientIds() {
        when(ingredientRepository.findAll()).thenReturn(List.of());
        stubIngredientCreation();
        when(menuItemIngredientRepository.count()).thenReturn(0L);

        seeder.run(null);

        ArgumentCaptor<List<RecipeItemRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(recipeService).replace(org.mockito.ArgumentMatchers.eq(1L), linesCaptor.capture());
        assertThat(linesCaptor.getValue()).isNotEmpty();
        assertThat(linesCaptor.getValue().get(0).ingredientId()).isNotNull();
    }

    private static IngredientRequest argThatNameIs(String name) {
        return org.mockito.ArgumentMatchers.argThat(req -> req != null && name.equals(req.name()));
    }
}
