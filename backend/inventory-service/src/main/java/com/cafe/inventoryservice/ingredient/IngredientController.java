package com.cafe.inventoryservice.ingredient;

import com.cafe.inventoryservice.ingredient.dto.IngredientRequest;
import com.cafe.inventoryservice.ingredient.dto.IngredientResponse;
import com.cafe.inventoryservice.ingredient.dto.StockInRequest;
import com.cafe.inventoryservice.ingredient.dto.StockMovementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ingredients")
@Tag(name = "Ingredients", description = "Raw stock items consumed by menu-item recipes")
public class IngredientController {

    private final IngredientService ingredientService;

    public IngredientController(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    @GetMapping
    @Operation(summary = "List all active ingredients")
    public List<IngredientResponse> findAll() {
        return ingredientService.findAll().stream().map(IngredientResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an ingredient (ADMIN only) - always starts at zero stock")
    public IngredientResponse create(@Valid @RequestBody IngredientRequest request) {
        return IngredientResponse.from(ingredientService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an ingredient's details (ADMIN only) - cannot change currentStock directly, see stock-in")
    public IngredientResponse update(@Parameter(description = "The ingredient's id", example = "7") @PathVariable @Positive Long id, @Valid @RequestBody IngredientRequest request) {
        return IngredientResponse.from(ingredientService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete an ingredient (ADMIN only)")
    public void delete(@Parameter(description = "The ingredient's id", example = "7") @PathVariable @Positive Long id) {
        ingredientService.delete(id);
    }

    @PostMapping("/{id}/stock-in")
    @Operation(summary = "Record incoming stock (ADMIN only) - the only way to increase currentStock")
    public IngredientResponse stockIn(@Parameter(description = "The ingredient's id", example = "7") @PathVariable @Positive Long id, @Valid @RequestBody StockInRequest request) {
        return IngredientResponse.from(ingredientService.stockIn(id, request.quantity()));
    }

    @GetMapping("/{id}/movements")
    @Operation(summary = "List stock movements for an ingredient, newest first")
    public List<StockMovementResponse> movements(@Parameter(description = "The ingredient's id", example = "7") @PathVariable @Positive Long id) {
        return ingredientService.findMovements(id).stream().map(StockMovementResponse::from).toList();
    }
}
