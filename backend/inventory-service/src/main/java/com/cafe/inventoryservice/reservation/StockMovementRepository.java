package com.cafe.inventoryservice.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByIngredientIdOrderByCreatedAtDesc(Long ingredientId);
}
