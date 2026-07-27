package com.cafe.inventoryservice.reservation;

import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.ingredient.Ingredient;
import com.cafe.inventoryservice.ingredient.IngredientRepository;
import com.cafe.inventoryservice.recipe.MenuItemIngredient;
import com.cafe.inventoryservice.recipe.MenuItemIngredientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The inventory-side half of the checkout saga's stock-reservation step (plan section 4):
 * an all-or-nothing deduction across every ingredient touched by an order. Menu items with
 * no recipe rows are always treated as in stock (soft, per the plan's accepted MVP tradeoff).
 */
@Service
public class StockReservationService {

    private static final String STEP_RESERVE_STOCK = "RESERVE_STOCK";
    private static final String MOVEMENT_REASON_CHECKOUT = "ORDER_CHECKOUT";

    private final IngredientRepository ingredientRepository;
    private final MenuItemIngredientRepository menuItemIngredientRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProcessedSagaStepRepository processedSagaStepRepository;

    public StockReservationService(IngredientRepository ingredientRepository,
                                    MenuItemIngredientRepository menuItemIngredientRepository,
                                    StockMovementRepository stockMovementRepository,
                                    ProcessedSagaStepRepository processedSagaStepRepository) {
        this.ingredientRepository = ingredientRepository;
        this.menuItemIngredientRepository = menuItemIngredientRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.processedSagaStepRepository = processedSagaStepRepository;
    }

    @Transactional
    public InventoryStockReservationReply reserve(Long orderId, String sagaAttemptId, List<OrderLineItem> items) {
        var alreadyProcessed = processedSagaStepRepository.findById(sagaAttemptId);
        if (alreadyProcessed.isPresent()) {
            ProcessedSagaStep step = alreadyProcessed.get();
            return step.isSuccess()
                    ? InventoryStockReservationReply.success(orderId, sagaAttemptId)
                    : InventoryStockReservationReply.failure(orderId, sagaAttemptId, step.getReason());
        }

        Map<Long, BigDecimal> requiredByIngredientId = computeRequiredQuantities(items);
        if (requiredByIngredientId.isEmpty()) {
            saveProcessedStep(orderId, sagaAttemptId, true, null);
            return InventoryStockReservationReply.success(orderId, sagaAttemptId);
        }

        List<Ingredient> lockedIngredients =
                ingredientRepository.findAllByIdForUpdate(new ArrayList<>(requiredByIngredientId.keySet()));

        for (Ingredient ingredient : lockedIngredients) {
            BigDecimal required = requiredByIngredientId.get(ingredient.getId());
            if (ingredient.getCurrentStock().compareTo(required) < 0) {
                String reason = "Insufficient stock: " + ingredient.getName();
                saveProcessedStep(orderId, sagaAttemptId, false, reason);
                return InventoryStockReservationReply.failure(orderId, sagaAttemptId, reason);
            }
        }

        for (Ingredient ingredient : lockedIngredients) {
            BigDecimal required = requiredByIngredientId.get(ingredient.getId());
            ingredient.setCurrentStock(ingredient.getCurrentStock().subtract(required));
            ingredientRepository.save(ingredient);
            stockMovementRepository.save(StockMovement.builder()
                    .ingredient(ingredient)
                    .changeAmount(required.negate())
                    .reason(MOVEMENT_REASON_CHECKOUT)
                    .referenceId(String.valueOf(orderId))
                    .build());
        }

        saveProcessedStep(orderId, sagaAttemptId, true, null);
        return InventoryStockReservationReply.success(orderId, sagaAttemptId);
    }

    private Map<Long, BigDecimal> computeRequiredQuantities(List<OrderLineItem> items) {
        List<Long> menuItemIds = items.stream().map(OrderLineItem::menuItemId).toList();
        List<MenuItemIngredient> recipeLines = menuItemIngredientRepository.findByMenuItemIdInWithIngredient(menuItemIds);
        Map<Long, List<MenuItemIngredient>> recipeByMenuItemId =
                recipeLines.stream().collect(Collectors.groupingBy(MenuItemIngredient::getMenuItemId));

        Map<Long, BigDecimal> required = new LinkedHashMap<>();
        for (OrderLineItem line : items) {
            for (MenuItemIngredient recipeLine : recipeByMenuItemId.getOrDefault(line.menuItemId(), List.of())) {
                BigDecimal needed = recipeLine.getQuantityRequired().multiply(BigDecimal.valueOf(line.quantity()));
                required.merge(recipeLine.getIngredient().getId(), needed, BigDecimal::add);
            }
        }
        return required;
    }

    private void saveProcessedStep(Long orderId, String sagaAttemptId, boolean success, String reason) {
        processedSagaStepRepository.save(ProcessedSagaStep.builder()
                .sagaAttemptId(sagaAttemptId)
                .orderId(orderId)
                .step(STEP_RESERVE_STOCK)
                .success(success)
                .reason(reason)
                .build());
    }
}
