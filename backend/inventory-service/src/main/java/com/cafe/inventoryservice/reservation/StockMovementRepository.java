package com.cafe.inventoryservice.reservation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

  List<StockMovement> findAllByIngredientIdOrderByCreatedAtDesc(Long ingredientId);
}
