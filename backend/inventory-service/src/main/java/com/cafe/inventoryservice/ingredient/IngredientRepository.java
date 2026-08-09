package com.cafe.inventoryservice.ingredient;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    List<Ingredient> findAllByActiveTrueOrderByNameAsc();

    /**
     * Row-locks the given ingredients for the duration of the transaction so a concurrent
     * checkout can't read a stale current_stock between this reservation's sufficiency
     * check and its deduction - the "all-or-nothing" guarantee under concurrency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Ingredient i WHERE i.id IN :ids")
    List<Ingredient> findAllByIdForUpdate(@Param("ids") List<Long> ids);
}
