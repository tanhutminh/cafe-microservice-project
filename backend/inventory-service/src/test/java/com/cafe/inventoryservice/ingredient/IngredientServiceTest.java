package com.cafe.inventoryservice.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.when;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.inventoryservice.reservation.StockMovementRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngredientServiceTest {

  @Mock private IngredientRepository ingredientRepository;
  @Mock private StockMovementRepository stockMovementRepository;

  private IngredientService service;

  @BeforeEach
  void setUp() {
    service = new IngredientService(ingredientRepository, stockMovementRepository);
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

  @Test
  void findAllByIdAsMap_returnsEveryIngredientKeyedByItsId() {
    Ingredient one = ingredient(1L);
    Ingredient two = ingredient(2L);
    when(ingredientRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(one, two));

    Map<Long, Ingredient> result = service.findAllByIdAsMap(List.of(1L, 2L));

    assertAll(
        () -> assertThat(result).hasSize(2),
        () -> assertThat(result.get(1L)).isSameAs(one),
        () -> assertThat(result.get(2L)).isSameAs(two));
  }

  @Test
  void findAllByIdAsMap_throwsForAnyIdTheRepositoryDidNotReturn() {
    when(ingredientRepository.findAllById(List.of(1L, 99L))).thenReturn(List.of(ingredient(1L)));

    assertThatThrownBy(() -> service.findAllByIdAsMap(List.of(1L, 99L)))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("Ingredient not found: 99");
  }

  @Test
  void findAllByIdAsMap_withNoIds_returnsAnEmptyMapWithoutThrowing() {
    when(ingredientRepository.findAllById(List.of())).thenReturn(List.of());

    Map<Long, Ingredient> result = service.findAllByIdAsMap(List.of());

    assertThat(result).isEmpty();
  }
}
