package com.cafe.inventoryservice.ingredient;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.inventoryservice.ingredient.dto.IngredientRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class IngredientService {

    private final IngredientRepository ingredientRepository;

    public IngredientService(IngredientRepository ingredientRepository) {
        this.ingredientRepository = ingredientRepository;
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
                .active(request.active())
                .build();
        return ingredientRepository.save(ingredient);
    }

    @Transactional
    public Ingredient update(Long id, IngredientRequest request) {
        Ingredient ingredient = findById(id);
        ingredient.setName(request.name());
        ingredient.setUnit(request.unit());
        ingredient.setActive(request.active());
        return ingredientRepository.save(ingredient);
    }

    @Transactional
    public void delete(Long id) {
        Ingredient ingredient = findById(id);
        ingredient.setActive(false);
        ingredientRepository.save(ingredient);
    }
}
