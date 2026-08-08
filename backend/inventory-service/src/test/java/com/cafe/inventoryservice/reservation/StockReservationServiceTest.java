package com.cafe.inventoryservice.reservation;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.ingredient.Ingredient;
import com.cafe.inventoryservice.ingredient.IngredientRepository;
import com.cafe.inventoryservice.recipe.MenuItemIngredient;
import com.cafe.inventoryservice.recipe.MenuItemIngredientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockReservationServiceTest {

    private static final Long ORDER_ID = 42L;
    private static final Long MENU_ITEM_ID = 1L;
    private static final Long INGREDIENT_ID = 10L;

    @Mock
    private IngredientRepository ingredientRepository;
    @Mock
    private MenuItemIngredientRepository menuItemIngredientRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;

    private StockReservationService service;

    @BeforeEach
    void setUp() {
        service = new StockReservationService(ingredientRepository, menuItemIngredientRepository, stockMovementRepository);
    }

    private List<OrderLineItem> oneLineItem() {
        return List.of(new OrderLineItem(MENU_ITEM_ID, 2));
    }

    private MenuItemIngredient recipeLine(BigDecimal quantityRequired) {
        Ingredient ingredient = Ingredient.builder()
                .id(INGREDIENT_ID)
                .name("Milk")
                .unit("ml")
                .currentStock(new BigDecimal("100.000"))
                .minStock(BigDecimal.ZERO)
                .reservedQuantity(BigDecimal.ZERO)
                .active(true)
                .build();
        return MenuItemIngredient.builder()
                .id(1L)
                .menuItemId(MENU_ITEM_ID)
                .ingredient(ingredient)
                .quantityRequired(quantityRequired)
                .build();
    }

    @Test
    void reserve_holdsQuantityWhenStockSufficient() {
        MenuItemIngredient recipeLine = recipeLine(new BigDecimal("10.000"));
        when(menuItemIngredientRepository.findByMenuItemIdInWithIngredient(List.of(MENU_ITEM_ID)))
                .thenReturn(List.of(recipeLine));
        when(ingredientRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(List.of(recipeLine.getIngredient()));

        InventoryStockReservationReply reply = service.reserve(ORDER_ID, oneLineItem());

        assertThat(reply.success()).isTrue();
        assertThat(reply.orderId()).isEqualTo(ORDER_ID);
        assertThat(recipeLine.getIngredient().getReservedQuantity()).isEqualByComparingTo("20.000");
        verify(ingredientRepository).save(recipeLine.getIngredient());
    }

    @Test
    void reserve_failsWithoutMutatingStockWhenInsufficient() {
        MenuItemIngredient recipeLine = recipeLine(new BigDecimal("1000.000"));
        when(menuItemIngredientRepository.findByMenuItemIdInWithIngredient(List.of(MENU_ITEM_ID)))
                .thenReturn(List.of(recipeLine));
        when(ingredientRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(List.of(recipeLine.getIngredient()));

        InventoryStockReservationReply reply = service.reserve(ORDER_ID, oneLineItem());

        assertThat(reply.success()).isFalse();
        assertThat(reply.reason()).contains("Milk");
        assertThat(recipeLine.getIngredient().getReservedQuantity()).isEqualByComparingTo("0.000");
        verify(ingredientRepository, never()).save(recipeLine.getIngredient());
    }

    @Test
    void reserve_treatsMenuItemWithNoRecipeAsAlwaysInStock() {
        when(menuItemIngredientRepository.findByMenuItemIdInWithIngredient(List.of(MENU_ITEM_ID)))
                .thenReturn(List.of());

        InventoryStockReservationReply reply = service.reserve(ORDER_ID, oneLineItem());

        assertThat(reply.success()).isTrue();
        verify(ingredientRepository, never()).findAllByIdForUpdate(anyList());
    }

    @Test
    void commit_deductsCurrentStockReleasesHoldAndRecordsMovement() {
        MenuItemIngredient recipeLine = recipeLine(new BigDecimal("10.000"));
        recipeLine.getIngredient().setReservedQuantity(new BigDecimal("20.000"));
        when(menuItemIngredientRepository.findByMenuItemIdInWithIngredient(List.of(MENU_ITEM_ID)))
                .thenReturn(List.of(recipeLine));
        when(ingredientRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(List.of(recipeLine.getIngredient()));

        InventoryStockCommitReply reply = service.commit(ORDER_ID, oneLineItem());

        assertThat(reply.success()).isTrue();
        assertThat(recipeLine.getIngredient().getCurrentStock()).isEqualByComparingTo("80.000");
        assertThat(recipeLine.getIngredient().getReservedQuantity()).isEqualByComparingTo("0.000");

        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movementCaptor.capture());
        assertThat(movementCaptor.getValue().getChangeAmount()).isEqualByComparingTo("-20.000");
        assertThat(movementCaptor.getValue().getReferenceId()).isEqualTo(String.valueOf(ORDER_ID));
    }

    @Test
    void release_restoresReservedQuantityWithoutTouchingCurrentStock() {
        MenuItemIngredient recipeLine = recipeLine(new BigDecimal("10.000"));
        recipeLine.getIngredient().setReservedQuantity(new BigDecimal("20.000"));
        BigDecimal currentStockBefore = recipeLine.getIngredient().getCurrentStock();
        when(menuItemIngredientRepository.findByMenuItemIdInWithIngredient(List.of(MENU_ITEM_ID)))
                .thenReturn(List.of(recipeLine));
        when(ingredientRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(List.of(recipeLine.getIngredient()));

        service.release(ORDER_ID, oneLineItem());

        assertThat(recipeLine.getIngredient().getReservedQuantity()).isEqualByComparingTo("0.000");
        assertThat(recipeLine.getIngredient().getCurrentStock()).isEqualByComparingTo(currentStockBefore);
        verify(stockMovementRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void release_noOpWhenMenuItemHasNoRecipe() {
        when(menuItemIngredientRepository.findByMenuItemIdInWithIngredient(List.of(MENU_ITEM_ID)))
                .thenReturn(List.of());

        service.release(ORDER_ID, oneLineItem());

        verify(ingredientRepository, never()).findAllByIdForUpdate(anyList());
    }
}
