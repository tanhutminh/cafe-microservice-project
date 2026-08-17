package com.cafe.inventoryservice.reservation;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.ingredient.Ingredient;
import com.cafe.inventoryservice.ingredient.IngredientRepository;
import com.cafe.inventoryservice.recipe.MenuItemIngredient;
import com.cafe.inventoryservice.recipe.MenuItemIngredientRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inventory-side of the order saga's two stock steps (soft-reservation model): {@link #reserve}
 * holds quantity (reservedQuantity) without touching currentStock, and {@link #commit} - the
 * payment leg - turns that hold into a real deduction. {@link #release} undoes a hold on cancel,
 * before it was ever committed. Available-to-reserve is always currentStock - reservedQuantity, not
 * currentStock alone, so two orders can't both hold the same physical stock. Menu items with no
 * recipe rows are always treated as in stock - an accepted MVP simplification, since there's
 * nothing to check against without a recipe.
 *
 * <p>Pure business logic only - idempotency/dedup for redelivered saga commands belongs to the
 * Transactional Inbox (see com.cafe.inventoryservice.inbox), not this class: by the time
 * InboxMessageProcessor calls one of these methods, the Inbox has already guaranteed it's the first
 * and only attempt for that correlationId.
 */
@Service
public class StockReservationService {

  private static final String MOVEMENT_REASON_CHECKOUT = "ORDER_CHECKOUT";

  private final IngredientRepository ingredientRepository;
  private final MenuItemIngredientRepository menuItemIngredientRepository;
  private final StockMovementRepository stockMovementRepository;

  public StockReservationService(
      IngredientRepository ingredientRepository,
      MenuItemIngredientRepository menuItemIngredientRepository,
      StockMovementRepository stockMovementRepository) {
    this.ingredientRepository = ingredientRepository;
    this.menuItemIngredientRepository = menuItemIngredientRepository;
    this.stockMovementRepository = stockMovementRepository;
  }

  /**
   * Verify leg: hold quantity against availability (currentStock - reservedQuantity). No
   * StockMovement - nothing physically changed yet.
   */
  @Transactional
  public InventoryStockReservationReply reserve(Long orderId, List<OrderLineItem> items) {
    Map<Long, BigDecimal> requiredByIngredientId = computeRequiredQuantities(items);
    if (requiredByIngredientId.isEmpty()) {
      return InventoryStockReservationReply.success(orderId);
    }

    List<Ingredient> lockedIngredients =
        ingredientRepository.findAllByIdForUpdate(new ArrayList<>(requiredByIngredientId.keySet()));

    for (Ingredient ingredient : lockedIngredients) {
      BigDecimal required = requiredByIngredientId.get(ingredient.getId());
      BigDecimal available =
          ingredient.getCurrentStock().subtract(ingredient.getReservedQuantity());
      if (available.compareTo(required) < 0) {
        return InventoryStockReservationReply.failure(
            orderId, "Insufficient stock: " + ingredient.getName());
      }
    }

    for (Ingredient ingredient : lockedIngredients) {
      BigDecimal required = requiredByIngredientId.get(ingredient.getId());
      ingredient.setReservedQuantity(ingredient.getReservedQuantity().add(required));
      ingredientRepository.save(ingredient);
    }

    return InventoryStockReservationReply.success(orderId);
  }

  /**
   * Payment leg: turn an existing hold into a real deduction. Does not re-check availability -
   * already validated at reserve time.
   */
  @Transactional
  public InventoryStockCommitReply commit(Long orderId, List<OrderLineItem> items) {
    Map<Long, BigDecimal> requiredByIngredientId = computeRequiredQuantities(items);
    if (requiredByIngredientId.isEmpty()) {
      return InventoryStockCommitReply.success(orderId);
    }

    List<Ingredient> lockedIngredients =
        ingredientRepository.findAllByIdForUpdate(new ArrayList<>(requiredByIngredientId.keySet()));

    for (Ingredient ingredient : lockedIngredients) {
      BigDecimal required = requiredByIngredientId.get(ingredient.getId());
      ingredient.setCurrentStock(ingredient.getCurrentStock().subtract(required));
      ingredient.setReservedQuantity(ingredient.getReservedQuantity().subtract(required));
      ingredientRepository.save(ingredient);
      stockMovementRepository.save(
          StockMovement.builder()
              .ingredient(ingredient)
              .changeAmount(required.negate())
              .reason(MOVEMENT_REASON_CHECKOUT)
              .referenceId(String.valueOf(orderId))
              .build());
    }

    return InventoryStockCommitReply.success(orderId);
  }

  /**
   * Cancel-after-CONFIRMED compensation: release a hold that was never committed. No StockMovement
   * - currentStock was never touched.
   */
  @Transactional
  public void release(Long orderId, List<OrderLineItem> items) {
    Map<Long, BigDecimal> requiredByIngredientId = computeRequiredQuantities(items);
    if (requiredByIngredientId.isEmpty()) {
      return;
    }

    List<Ingredient> lockedIngredients =
        ingredientRepository.findAllByIdForUpdate(new ArrayList<>(requiredByIngredientId.keySet()));
    for (Ingredient ingredient : lockedIngredients) {
      BigDecimal required = requiredByIngredientId.get(ingredient.getId());
      ingredient.setReservedQuantity(ingredient.getReservedQuantity().subtract(required));
      ingredientRepository.save(ingredient);
    }
  }

  private Map<Long, BigDecimal> computeRequiredQuantities(List<OrderLineItem> items) {
    List<Long> menuItemIds = items.stream().map(OrderLineItem::menuItemId).toList();
    List<MenuItemIngredient> recipeLines =
        menuItemIngredientRepository.findByMenuItemIdInWithIngredient(menuItemIds);
    Map<Long, List<MenuItemIngredient>> recipeByMenuItemId =
        recipeLines.stream().collect(Collectors.groupingBy(MenuItemIngredient::getMenuItemId));

    Map<Long, BigDecimal> required = new LinkedHashMap<>();
    for (OrderLineItem line : items) {
      for (MenuItemIngredient recipeLine :
          recipeByMenuItemId.getOrDefault(line.menuItemId(), List.of())) {
        BigDecimal needed =
            recipeLine.getQuantityRequired().multiply(BigDecimal.valueOf(line.quantity()));
        required.merge(recipeLine.getIngredient().getId(), needed, BigDecimal::add);
      }
    }
    return required;
  }
}
