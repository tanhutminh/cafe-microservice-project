package com.cafe.inventoryservice.ingredient;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.inventoryservice.ingredient.dto.IngredientRequest;
import com.cafe.inventoryservice.reservation.StockMovement;
import com.cafe.inventoryservice.reservation.StockMovementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class IngredientService {

    private static final String MOVEMENT_REASON_STOCK_IN = "STOCK_IN";

    private final IngredientRepository ingredientRepository;
    private final StockMovementRepository stockMovementRepository;

    public IngredientService(IngredientRepository ingredientRepository, StockMovementRepository stockMovementRepository) {
        this.ingredientRepository = ingredientRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    public List<Ingredient> findAll() {
        return ingredientRepository.findAllByActiveTrueOrderByNameAsc();
    }

    public Ingredient findById(Long id) {
        return ingredientRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", id));
    }

    @Transactional
    public Ingredient create(IngredientRequest request) {
        Ingredient ingredient = Ingredient.builder()
                .name(request.name())
                .unit(request.unit())
                .currentStock(BigDecimal.ZERO)
                .minStock(request.minStock())
                .reservedQuantity(BigDecimal.ZERO)
                .active(request.active())
                .build();
        return ingredientRepository.save(ingredient);
    }

    @Transactional
    public Ingredient update(Long id, IngredientRequest request) {
        Ingredient ingredient = findById(id);
        ingredient.setName(request.name());
        ingredient.setUnit(request.unit());
        ingredient.setMinStock(request.minStock());
        ingredient.setActive(request.active());
        return ingredientRepository.save(ingredient);
    }

    @Transactional
    public void delete(Long id) {
        Ingredient ingredient = findById(id);
        ingredient.setActive(false);
        ingredientRepository.save(ingredient);
    }

    /**
     * Row-locks the single ingredient (reusing the same pessimistic-write query the checkout
     * saga uses) so a stock-in can't race a concurrent saga deduction into a lost update.
     */
    @Transactional
    public Ingredient stockIn(Long id, BigDecimal quantity) {
        Ingredient ingredient = ingredientRepository.findAllByIdForUpdate(List.of(id)).stream()
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", id));
        ingredient.setCurrentStock(ingredient.getCurrentStock().add(quantity));
        ingredientRepository.save(ingredient);
        stockMovementRepository.save(StockMovement.builder()
                .ingredient(ingredient)
                .changeAmount(quantity)
                .reason(MOVEMENT_REASON_STOCK_IN)
                .build());
        return ingredient;
    }

    public List<StockMovement> findMovements(Long id) {
        findById(id);
        return stockMovementRepository.findByIngredientIdOrderByCreatedAtDesc(id);
    }
}
